// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Accumulates how long each pipeline handler spends on inbound packets. Each probe stamps the
 * clock as a packet crosses it; the gap between two probes is the time the handler between them
 * took. One event loop writes, but the command thread reads the totals for the report - hence the
 * concurrent structures.
 */
public final class ProfileSession {
    public record Entry(String handler, String plugin, long samples, double averageMillis, double totalMillis) {}

    private final Map<String, LongAdder> totalNanos = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> samples = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    private volatile long lastStamp = 0L;

    public void registerOwner(String handler, String plugin) {
        if (plugin != null) this.owners.put(handler, plugin);
    }

    /**
     * Called as a packet reaches a probe. {@code previousHandler} is the handler the
     * packet just left, or null for the probe at the head of the pipeline.
     */
    public void mark(String previousHandler) {
        final long now = System.nanoTime();
        final long previous = this.lastStamp;
        this.lastStamp = now;

        if (previousHandler == null || previous == 0L) return;

        final long delta = now - previous;

        // A negative or absurd delta means the packet did not come straight through
        // (a different packet interleaved, or the pipeline changed). Dropping those
        // beats reporting a handler as catastrophically slow because of a race.
        if (delta <= 0 || delta > 1_000_000_000L) return;

        this.totalNanos.computeIfAbsent(previousHandler, k -> new LongAdder()).add(delta);
        this.samples.computeIfAbsent(previousHandler, k -> new LongAdder()).increment();
    }

    /** Worst average first, which is the order an admin wants to read. */
    public List<Entry> results() {
        final List<Entry> out = new ArrayList<>();

        for (final Map.Entry<String, LongAdder> entry : this.totalNanos.entrySet()) {
            final String handler = entry.getKey();
            final long total = entry.getValue().sum();
            final LongAdder counter = this.samples.get(handler);
            final long count = counter == null ? 0 : counter.sum();

            if (count == 0) continue;

            final double avgMs = (total / (double) count) / 1_000_000.0;
            out.add(new Entry(handler, this.owners.get(handler), count, avgMs, total / 1_000_000.0));
        }
        out.sort((a, b) -> Double.compare(b.averageMillis(), a.averageMillis()));

        return out;
    }

    public boolean isEmpty() {
        return this.totalNanos.isEmpty();
    }
}

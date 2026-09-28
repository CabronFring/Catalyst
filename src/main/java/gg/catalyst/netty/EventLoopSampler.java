// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * How responsive the network threads are. Netty's event loops have no tick of their own, so this
 * gives them one: a task asks to run every 50ms on each loop players connect through, and records
 * how late it really ran. A loop busy with something slow delays every connection on it by that
 * much. The figure is that lateness in ms, also shown on the TPS scale (20 = on time) as a familiar frame.
 */
public final class EventLoopSampler {
    private static final long PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    public record LoopResult(String thread, int players, long samples,
                             double averageDelayMs, double worstDelayMs, double tpsEquivalent) {}

    /** Touched only from its own event loop's thread while running; read after stop(). */
    private static final class Probe {
        final EventLoop loop;
        int players;
        volatile String thread = "?";
        long last, samples, totalGapNanos, totalLateNanos, worstLateNanos;
        ScheduledFuture<?> task;

        Probe(EventLoop loop) { this.loop = loop; }

        void tick() {
            final long now = System.nanoTime();

            if (this.last != 0) {
                final long gap = now - this.last;
                final long late = Math.max(0, gap - PERIOD_NANOS);
                this.samples++;
                this.totalGapNanos += gap;
                this.totalLateNanos += late;
                this.worstLateNanos = Math.max(this.worstLateNanos, late);
            } else {
                this.thread = Thread.currentThread().getName();
            }
            this.last = now;
        }
    }

    private final List<Probe> probes;

    private EventLoopSampler(List<Probe> probes) {
        this.probes = probes;
    }

    /** One probe per distinct event loop among these players' connections. */
    public static EventLoopSampler start(Iterable<? extends Player> players) {
        final Map<EventLoop, Probe> byLoop = new IdentityHashMap<>();

        for (final Player player : players) {
            Channel channel;

            try {
                channel = (Channel) NettyInspector.channelFor(player);
            } catch (NettyInspector.Unavailable | ClassCastException e) {
                continue;
            }

            if (channel == null || !channel.isActive()) continue;
            byLoop.computeIfAbsent(channel.eventLoop(), Probe::new).players++;
        }
        final List<Probe> probes = new ArrayList<>(byLoop.values());

        for (final Probe p : probes)
            p.task = p.loop.scheduleAtFixedRate(p::tick, 0, PERIOD_NANOS, TimeUnit.NANOSECONDS);

        return new EventLoopSampler(probes);
    }

    public boolean isEmpty() { return this.probes.isEmpty(); }

    /** Cancels every probe; nothing is left scheduled on the server's threads. */
    public List<LoopResult> stop() {
        final List<LoopResult> results = new ArrayList<>();

        for (final Probe p : this.probes) {
            if (p.task != null) p.task.cancel(false);

            // Read on the loop itself, so the figures are the ones its thread wrote.
            try {
                results.add(p.loop.submit(() -> result(p)).get(2, TimeUnit.SECONDS));
            } catch (Exception busyOrGone) {
                // A loop too stuck to answer within 2s is the worst result there is.
                results.add(new LoopResult(p.thread, p.players, p.samples, Double.NaN, Double.NaN, 0));
            }
        }
        results.sort(Comparator.comparingDouble((LoopResult r) -> Double.isNaN(r.averageDelayMs()) ? Double.MAX_VALUE : r.averageDelayMs()).reversed());

        return results;
    }

    private static LoopResult result(Probe p) {
        if (p.samples == 0) return new LoopResult(p.thread, p.players, 0, 0, 0, 20);
        final double avgGap = p.totalGapNanos / (double) p.samples;

        return new LoopResult(p.thread, p.players, p.samples,
                p.totalLateNanos / (double) p.samples / 1e6,
                p.worstLateNanos / 1e6,
                Math.min(20.0, 20.0 * PERIOD_NANOS / avgGap));
    }
}

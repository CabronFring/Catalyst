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
 * gives them one: a task asks to run 50ms after it last ran on each loop players connect through,
 * and records how late it really ran. A loop busy with something slow delays every connection on
 * it by that much. The figure is that lateness in ms, also shown on the TPS scale (20 = on time)
 * as a familiar frame.
 *
 * <p>Nothing here ever waits on a network thread: the probes publish their figures under a lock
 * that any thread can read, so stopping is instant even when a loop is stuck.
 */
public final class EventLoopSampler {
    private static final long PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** Silent for this long when the sample ends, a loop counts as stuck rather than slow. */
    private static final long STUCK_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** {@code stuckMs} is how long the loop had gone without running the probe when the sample ended, 0 if it was not stuck. */
    public record LoopResult(String thread, int players, long samples,
                             double averageDelayMs, double worstDelayMs, double tpsEquivalent, double stuckMs) {
        public boolean stuck() { return this.stuckMs > 0; }
    }

    /** Written by its event loop's thread, read by whoever stops the sample; both under its lock. */
    static final class Probe {
        final EventLoop loop;
        final long started;
        int players;
        // Named by the loop's own thread the first time the probe runs there.
        String thread = "(never ran)";
        long last, samples, totalGapNanos, totalLateNanos, worstLateNanos;
        ScheduledFuture<?> task;

        Probe(EventLoop loop, long started) {
            this.loop = loop;
            this.started = started;
        }

        synchronized void tick(long now) {
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

        synchronized LoopResult result(long now) {
            final long silent = now - (this.last != 0 ? this.last : this.started);
            final double stuckMs = silent >= STUCK_NANOS ? silent / 1e6 : 0;

            if (this.samples == 0) return new LoopResult(this.thread, this.players, 0, 0, 0, stuckMs > 0 ? 0 : 20, stuckMs);
            final double avgGap = this.totalGapNanos / (double) this.samples;

            return new LoopResult(this.thread, this.players, this.samples,
                    this.totalLateNanos / (double) this.samples / 1e6,
                    this.worstLateNanos / 1e6,
                    Math.min(20.0, 20.0 * PERIOD_NANOS / avgGap),
                    stuckMs);
        }
    }

    private final List<Probe> probes;

    private EventLoopSampler(List<Probe> probes) {
        this.probes = probes;
    }

    /** One probe per distinct event loop among these players' connections. */
    public static EventLoopSampler start(Iterable<? extends Player> players) {
        final Map<EventLoop, Probe> byLoop = new IdentityHashMap<>();
        final long now = System.nanoTime();

        for (final Player player : players) {
            Channel channel;

            try {
                channel = (Channel) NettyInspector.channelFor(player);
            } catch (NettyInspector.Unavailable | ClassCastException e) {
                continue;
            }

            if (channel == null || !channel.isActive()) continue;
            byLoop.computeIfAbsent(channel.eventLoop(), loop -> new Probe(loop, now)).players++;
        }

        return schedule(new ArrayList<>(byLoop.values()));
    }

    /** Samples these loops directly, with no players behind them. */
    static EventLoopSampler forLoops(Collection<? extends EventLoop> loops) {
        final long now = System.nanoTime();
        final List<Probe> probes = new ArrayList<>();

        for (final EventLoop loop : loops) probes.add(new Probe(loop, now));
        return schedule(probes);
    }

    private static EventLoopSampler schedule(List<Probe> probes) {
        // Fixed delay, not fixed rate: after a stall a fixed-rate task runs back to back to catch
        // up, which wastes the loop's time and drowns the stall in near-zero gaps.
        for (final Probe p : probes)
            p.task = p.loop.scheduleWithFixedDelay(() -> p.tick(System.nanoTime()), 0, PERIOD_NANOS, TimeUnit.NANOSECONDS);

        return new EventLoopSampler(probes);
    }

    public boolean isEmpty() { return this.probes.isEmpty(); }

    /** Cancels every probe and returns at once; nothing is left scheduled on the server's threads. */
    public List<LoopResult> stop() {
        final long now = System.nanoTime();
        final List<LoopResult> results = new ArrayList<>();

        for (final Probe p : this.probes) {
            if (p.task != null) p.task.cancel(false);
            results.add(p.result(now));
        }
        // Stuck loops first, longest stuck first, then the slowest.
        results.sort(Comparator.comparingDouble((LoopResult r) -> r.stuck() ? 1e12 + r.stuckMs() : r.averageDelayMs()).reversed());

        return results;
    }
}

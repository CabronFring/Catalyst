// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

import io.netty.channel.DefaultEventLoop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopSamplerTest {
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void lateTicksAreAveraged() {
        final EventLoopSampler.Probe p = new EventLoopSampler.Probe(null, 0);
        p.tick(1_000 * MS);
        p.tick(1_050 * MS); // on time
        p.tick(1_130 * MS); // 30ms late
        final EventLoopSampler.LoopResult r = p.result(1_140 * MS);

        assertEquals(2, r.samples());
        assertEquals(15.0, r.averageDelayMs(), 0.001);
        assertEquals(30.0, r.worstDelayMs(), 0.001);
        assertFalse(r.stuck());
    }

    @Test
    void silentLoopIsStuck() {
        final EventLoopSampler.Probe p = new EventLoopSampler.Probe(null, 0);
        p.tick(1_000 * MS);
        final EventLoopSampler.LoopResult r = p.result(4_000 * MS);

        assertTrue(r.stuck());
        assertEquals(3_000.0, r.stuckMs(), 0.001);
    }

    @Test
    void stopNeverWaitsOnABlockedLoop() throws Exception {
        final DefaultEventLoop loop = new DefaultEventLoop();
        final CountDownLatch release = new CountDownLatch(1);

        try {
            final EventLoopSampler sampler = EventLoopSampler.forLoops(List.of(loop));
            Thread.sleep(200);
            loop.execute(() -> {
                try { release.await(); } catch (InterruptedException ignored) {}
            });
            Thread.sleep(2_300);

            final long before = System.nanoTime();
            final List<EventLoopSampler.LoopResult> results = sampler.stop();
            final long tookMs = (System.nanoTime() - before) / 1_000_000;

            assertTrue(tookMs < 100, "stop() took " + tookMs + "ms");
            assertTrue(results.get(0).stuck(), "a loop blocked for 2.3s should count as stuck");
        } finally {
            release.countDown();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        }
    }
}

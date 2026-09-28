// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.report;

import org.junit.jupiter.api.Test;

import static gg.catalyst.report.Rating.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each threshold is checked on both sides, since off-by-one at the boundary is the usual bug. */
class RatingTest {
    @Test
    void tps() {
        assertEquals(GOOD, forTps(20.0));
        assertEquals(GOOD, forTps(19.0));
        assertEquals(FAIR, forTps(18.99));
        assertEquals(FAIR, forTps(16.0));
        assertEquals(POOR, forTps(15.99));
        assertEquals(POOR, forTps(0));
    }

    @Test
    void ping() {
        assertEquals(GOOD, forPing(99));
        assertEquals(FAIR, forPing(100));
        assertEquals(FAIR, forPing(249));
        assertEquals(POOR, forPing(250));
    }

    @Test
    void handlerMillis() {
        assertEquals(GOOD, forHandlerMillis(0.099));
        assertEquals(FAIR, forHandlerMillis(0.10));
        assertEquals(FAIR, forHandlerMillis(0.499));
        assertEquals(POOR, forHandlerMillis(0.50));
    }

    @Test
    void chunkEventsPerSecond() {
        assertEquals(GOOD, forChunkEventsPerSecond(49.9));
        assertEquals(FAIR, forChunkEventsPerSecond(50));
        assertEquals(FAIR, forChunkEventsPerSecond(499));
        assertEquals(POOR, forChunkEventsPerSecond(500));
    }

    @Test
    void loopDelayMillis() {
        assertEquals(GOOD, forLoopDelayMillis(1.99));
        assertEquals(FAIR, forLoopDelayMillis(2));
        assertEquals(FAIR, forLoopDelayMillis(9.99));
        assertEquals(POOR, forLoopDelayMillis(10));
    }

    @Test
    void mobsInChunk() {
        assertEquals(GOOD, forMobsInChunk(19));
        assertEquals(FAIR, forMobsInChunk(20));
        assertEquals(FAIR, forMobsInChunk(39));
        assertEquals(POOR, forMobsInChunk(40));
    }
}

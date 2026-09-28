// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotNamesTest {
    @Test
    void theLaneIsTheNumberTheNameEndsInLessOne() {
        assertEquals(0, BotStage.laneOf("cat_bot_1"));
        assertEquals(11, BotStage.laneOf("cat_bot_12"));
        assertEquals(49, BotStage.laneOf("tester_50"));
        assertEquals(2, BotStage.laneOf("bot3"));
    }

    @Test
    void aNameWithNoNumberFallsBackToTheFirstLane() {
        assertEquals(0, BotStage.laneOf("someone"));
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotStageVersionTest {
    @Test
    void classicBukkitVersion() {
        assertEquals("1.21.11", BotStage.minecraftVersion("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("1.20.4", BotStage.minecraftVersion("1.20.4-R0.1-SNAPSHOT"));
    }

    @Test
    void paper26Version() {
        // What Paper 26.2 build 129 really reports.
        assertEquals("26.2", BotStage.minecraftVersion("26.2.build.129-stable"));
    }
}

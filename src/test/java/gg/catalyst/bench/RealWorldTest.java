// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RealWorldTest {
    private static RealWorld.Candidate c(String name, boolean overworld, boolean bench, long mb) {
        return new RealWorld.Candidate(name, overworld, bench, mb << 20);
    }

    @Test
    void aVoidHubAsDefaultWorldLosesToTheSurvivalWorld() {
        final var worlds = List.of(
                c("hub", true, false, 2), // the default world, first in the list
                c("hub_nether", false, false, 0),
                c("survival", true, false, 3500),
                c("survival_nether", false, false, 900),
                c("catalyst_bench", true, true, 9000));
        assertEquals("survival", RealWorld.choose(worlds, "").name());
    }

    @Test
    void neverPicksANetherOrABenchWorld() {
        final var worlds = List.of(c("world_nether", false, false, 5000), c("catalyst_bench_terrain", true, true, 5000),
                c("world", true, false, 10));
        assertEquals("world", RealWorld.choose(worlds, null).name());
        assertNull(RealWorld.choose(List.of(c("world_nether", false, false, 5000)), null));
    }

    @Test
    void theConfigWins() {
        final var worlds = List.of(c("hub", true, false, 2), c("survival", true, false, 3500));
        final RealWorld.Choice choice = RealWorld.choose(worlds, " Hub ");
        assertEquals("hub", choice.name());
        assertTrue(choice.why().contains("benchmark.terrain-world"));
        assertNull(RealWorld.choose(worlds, "missing"));
        assertTrue(RealWorld.refusal("missing").contains("missing"));
    }
}

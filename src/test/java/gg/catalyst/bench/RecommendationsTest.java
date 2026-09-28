// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import gg.catalyst.optimization.OptimizationLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationsTest {
    private static Recommendations.Inputs inputs(double liveMs, Recommendations.Census census,
                                                 Map<String, OptimizationLevel> pending, Double perRealClient) {
        // Per-unit costs close to what the Paper 1.21.11 test server measured.
        return new Recommendations.Inputs(liveMs, 0.04, 0.01, 0.08, 0.04, perRealClient, 81, 6.0, null, null, null, null,
                census, pending, true);
    }

    @Test
    void ranksByEstimatedCostBiggestFirst() {
        final var census = new Recommendations.Census(300, 100, 5, 405, 0, 0, 0);
        final List<Recommendations.Item> items = Recommendations.build(inputs(20, census, Map.of(), 0.3));
        assertEquals("Villagers", items.get(0).title()); // 300 x 0.04 = 12ms
        assertEquals("Players", items.get(1).title()); // 5 x 0.3 = 1.5ms
        assertEquals("Mobs", items.get(2).title()); // 100 x 0.01 = 1ms
        assertEquals(12.0, items.get(0).estimatedMs(), 1e-9);
    }

    @Test
    void pendingConfigChangeBecomesTheFixAtTheHighestLevelNeeded() {
        final var census = new Recommendations.Census(300, 0, 0, 0, 0, 0, 0);
        final var pending = Map.of("spigot.tick-inactive-villagers", OptimizationLevel.SAFE,
                "paper.entity-behavior", OptimizationLevel.MODERATE);
        final Recommendations.Item villagers = Recommendations.build(inputs(15, census, pending, null)).get(0);
        // The exact settings for this cause, not a whole level.
        assertEquals("/catalyst config fix spigot.tick-inactive-villagers paper.entity-behavior", villagers.command());
        assertTrue(villagers.fix().contains("spigot.tick-inactive-villagers"));
    }

    @Test
    void withNothingPendingTheAdviceIsPlainTextWithNoCommand() {
        final var census = new Recommendations.Census(300, 0, 0, 0, 0, 0, 0);
        final Recommendations.Item villagers = Recommendations.build(inputs(15, census, Map.of(), null)).get(0);
        assertNull(villagers.command());
        assertTrue(villagers.fix().toLowerCase().contains("villager"));
    }

    @Test
    void realClientCostIsPreferredOverTheFootprintEstimate() {
        final var census = new Recommendations.Census(0, 0, 10, 810, 0, 0, 0);
        final Recommendations.Item players = Recommendations.build(inputs(5, census, Map.of(), 0.3)).stream()
                .filter(i -> i.title().equals("Players")).findFirst().orElseThrow();
        assertEquals(3.0, players.estimatedMs(), 1e-9);
        assertTrue(players.evidence().contains("real clients"));
    }

    @Test
    void chunksBeyondThePlayersOwnAreCountedAsUnattended() {
        // 2 players keep 162 chunks; the other 2,000 have nobody near them.
        final var census = new Recommendations.Census(0, 0, 2, 2162, 0, 0, 0);
        final Recommendations.Item chunks = Recommendations.build(inputs(5, census, Map.of(), null)).stream()
                .filter(i -> i.title().startsWith("Chunks loaded")).findFirst().orElseThrow();
        assertEquals(2000 * 0.04 / 81, chunks.estimatedMs(), 1e-9);
    }

    @Test
    void loadTheBenchmarkCannotExplainPointsAtTheProfiler() {
        final var census = new Recommendations.Census(10, 0, 0, 0, 0, 0, 0);
        final List<Recommendations.Item> items = Recommendations.build(inputs(30, census, Map.of(), null));
        final Recommendations.Item unexplained = items.stream()
                .filter(i -> i.title().equals("Unexplained load")).findFirst().orElseThrow();
        assertEquals("/catalyst perf profile", unexplained.command());
        assertEquals(unexplained, items.get(0), "30ms unexplained should outrank 0.4ms of villagers");
    }

    @Test
    void aQuietServerGetsNoRecommendations() {
        final var census = new Recommendations.Census(3, 10, 1, 81, 20, 0, 0);
        assertTrue(Recommendations.build(inputs(1.2, census, Map.of(), 0.3)).isEmpty());
    }

    @Test
    void adviceWithoutAnEstimateIsListedAfterEstimates() {
        final var census = new Recommendations.Census(300, 0, 0, 0, 800, 0, 0);
        final var pending = Map.of("paper.hopper-occlusion", OptimizationLevel.SAFE);
        final List<Recommendations.Item> items = Recommendations.build(inputs(15, census, pending, null));
        assertEquals("Villagers", items.get(0).title());
        assertTrue(Double.isNaN(items.get(items.size() - 1).estimatedMs()));
    }

    @Test
    void measuredHoppersAndDroppedItemsGetEstimates() {
        final var census = new Recommendations.Census(0, 0, 0, 0, 4000, 3000, 0);
        final var in = new Recommendations.Inputs(6, null, null, null, null, null, 81, 6.0, 0.001, 0.0005, null, null,
                census, Map.of(), true);
        final List<Recommendations.Item> items = Recommendations.build(in);
        assertEquals("Hoppers", items.get(0).title());
        assertEquals(4.0, items.get(0).estimatedMs(), 1e-9);
        assertEquals("Dropped items", items.get(1).title());
        assertEquals(1.5, items.get(1).estimatedMs(), 1e-9);
    }

    @Test
    void measuredMinecartsGetAnEstimate() {
        final var census = new Recommendations.Census(0, 0, 0, 0, 0, 0, 500);
        final var in = new Recommendations.Inputs(6, null, null, null, null, null, 81, 6.0, null, null, 0.008, null,
                census, Map.of(), true);
        final Recommendations.Item carts = Recommendations.build(in).get(0);
        assertEquals("Minecarts", carts.title());
        assertEquals(4.0, carts.estimatedMs(), 1e-9);
        assertNull(carts.command());
    }

    @Test
    void explosionsAreRaisedOnlyWithTheirFixPending() {
        final var census = new Recommendations.Census(0, 0, 0, 0, 0, 0, 0);
        final var in = new Recommendations.Inputs(1, null, null, null, null, null, 81, 6.0, null, null, null, 0.05,
                census, Map.of("paper.optimize-explosions", OptimizationLevel.SAFE), true);
        final Recommendations.Item blasts = Recommendations.build(in).get(0);
        assertEquals("Explosions", blasts.title());
        assertTrue(blasts.evidence().contains("1.0ms per explosion"));
        assertEquals("/catalyst config fix paper.optimize-explosions", blasts.command());

        final var nothingPending = new Recommendations.Inputs(1, null, null, null, null, null, 81, 6.0, null, null, null, 0.05,
                census, Map.of(), true);
        assertTrue(Recommendations.build(nothingPending).isEmpty());
    }

    @Test
    void medianIgnoresNaNAndHandlesEvenCounts() {
        assertEquals(2.0, Benchmark.median(List.of(3.0, 1.0, 2.0)));
        assertEquals(2.5, Benchmark.median(List.of(4.0, 1.0, 2.0, 3.0)));
        assertEquals(1.0, Benchmark.median(List.of(Double.NaN, 1.0)));
        assertTrue(Double.isNaN(Benchmark.median(List.of())));
    }
}

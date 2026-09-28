// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import gg.catalyst.spark.ProfileAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BotFindingsTest {
    private static ProfileAnalysis.Result profile(List<ProfileAnalysis.Share> plugins,
                                                  List<ProfileAnalysis.Share> categories,
                                                  Map<String, List<ProfileAnalysis.HotMethod>> listeners) {
        // 400 ticks: a listener with 200ms in total is 0.5ms a tick.
        return new ProfileAnalysis.Result(20, 400, 2000, 5, categories, plugins, List.of(), List.of(), listeners, false, List.of());
    }

    private static ProfileAnalysis.Share share(String name, double perTick, double percent) {
        return new ProfileAnalysis.Share(name, perTick * 400, perTick, percent, "");
    }

    @Test
    void namesAHeavyPluginWithItsCostliestListeners() {
        final var p = profile(List.of(share("SlowPlugin", 0.8, 16), share("Tiny", 0.1, 2)), List.of(),
                Map.of("SlowPlugin", List.of(
                        new ProfileAnalysis.HotMethod("MoveListener.onMove", "SlowPlugin", 200, 10),
                        new ProfileAnalysis.HotMethod("Other.onChat", "SlowPlugin", 4, 0.2))));
        final var found = BotFindings.find(p, 5, Set.of("catalyst", "spark"), 0, 0, 0, 0, false);
        assertEquals(1, found.size(), "Tiny is below the threshold");
        assertTrue(found.get(0).title().startsWith("SlowPlugin took 0.80ms"));
        assertTrue(found.get(0).evidence().contains("MoveListener.onMove 0.50ms"));
        assertFalse(found.get(0).evidence().contains("onChat"), "0.01ms listeners are noise");
    }

    @Test
    void neverNamesCatalystOrSpark() {
        final var p = profile(List.of(share("Catalyst", 2, 40), share("spark", 1, 20)), List.of(), Map.of());
        assertTrue(BotFindings.find(p, 5, Set.of("catalyst", "spark"), 0, 0, 0, 0, false).isEmpty());
    }

    @Test
    void chunkWorkAndGenerationBacklogNeedClearMargins() {
        final var heavy = profile(List.of(), List.of(share("Chunk loading and generation", 3, 60)), Map.of());
        final var light = profile(List.of(), List.of(share("Chunk loading and generation", 0.5, 60)), Map.of());
        assertEquals(1, BotFindings.find(heavy, 5, Set.of(), 0, 0, 0, 0, false).size());
        assertTrue(BotFindings.find(light, 5, Set.of(), 0, 0, 0, 0, false).isEmpty());

        assertEquals(1, BotFindings.find(null, 5, Set.of(), 1000, 600, 0, 0, false).size(), "600 of 1000 is behind");
        assertTrue(BotFindings.find(null, 5, Set.of(), 1000, 950, 0, 0, false).isEmpty(), "95% kept up");
        assertTrue(BotFindings.find(null, 5, Set.of(), 20, 0, 0, 0, false).isEmpty(), "too few chunks to judge");
    }

    @Test
    void chunkFindingReflectsHowManyWereActuallyGenerated() {
        final var heavy = profile(List.of(), List.of(share("Chunk loading and generation", 3, 60)), Map.of());

        // Mostly newly generated: exploring new land, so pregenerating helps.
        final var generating = BotFindings.find(heavy, 5, Set.of(), 0, 0, 900, 1000, false).get(0);

        // Almost all read from disk: existing terrain, so pregenerating does nothing.
        final var loading = BotFindings.find(heavy, 5, Set.of(), 0, 0, 5, 1000, false).get(0);

        assertTrue(generating.title().contains("generating"));
        assertTrue(generating.advice().contains("Pregenerate"));
        assertTrue(generating.evidence().contains("900 of 1,000"));
        assertTrue(loading.title().contains("sending") && !loading.title().contains("generating"));
        assertFalse(loading.advice().contains("Pregenerate"), "pregenerating cannot help already-generated terrain");
    }

    @Test
    void entityTrackingIsFlaggedOnSpreadRunsOnly() {
        final var heavy = profile(List.of(), List.of(share("Entity tracking", 8, 40)), Map.of());
        final var spread = BotFindings.find(heavy, 60, Set.of(), 0, 0, 0, 0, false);
        assertEquals(1, spread.size());
        assertTrue(spread.get(0).advice().contains("entity-tracking-range"));

        // Clustered: the bots track each other, so no spigot.yml advice - the report explains it.
        assertTrue(BotFindings.find(heavy, 60, Set.of(), 0, 0, 0, 0, true).isEmpty());
    }
}

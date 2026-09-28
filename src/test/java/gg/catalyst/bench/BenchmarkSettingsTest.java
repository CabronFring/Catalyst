// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import gg.catalyst.bench.Benchmark.Intensity;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkSettingsTest {
    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(text);

        return c;
    }

    @Test
    void anEmptyConfigIsTheLongStandingMediumLoad() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml(""), null);
        assertEquals(Intensity.MEDIUM, s.intensity());
        assertEquals(100, s.villagers());
        assertEquals(1000, s.hoppers());
        assertEquals(100, s.minecarts());
        assertEquals(30, s.generatedChunks());
    }

    @Test
    void theIntensityPicksEachStagesAmountForThatLevel() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("""
                benchmark:
                  intensity: harsh
                  stages:
                    villagers:
                      LOW: 10
                      MEDIUM: 20
                      HARSH: 400
                """), null);
        assertEquals(400, s.villagers());
        assertEquals(600, s.cows(), "a stage not listed uses the built-in HARSH amount");
    }

    @Test
    void theRunsLevelOverridesTheConfiguredOne() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("""
                benchmark:
                  intensity: HARSH
                  stages:
                    villagers:
                      LOW: 7
                      HARSH: 400
                """), Intensity.LOW);
        assertEquals(Intensity.LOW, s.intensity());
        assertEquals(7, s.villagers());
    }

    @Test
    void aLevelLeftOutUsesTheBuiltInAmountAndZeroSkips() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("""
                benchmark:
                  stages:
                    villagers:
                      low: 5
                    cows:
                      MEDIUM: 0
                """), null);
        assertEquals(100, s.villagers());
        assertEquals(0, s.cows());
    }

    @Test
    void aStageSwitchedOffStaysOffAtEveryLevel() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("""
                benchmark:
                  stages:
                    explosions:
                      enabled: false
                      HARSH: 8
                """), Intensity.HARSH);
        assertEquals(0, s.explosions());
        assertEquals(300, s.villagers());
    }

    @Test
    void aPlainNumberIsOneAmountForEveryLevel() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("""
                benchmark:
                  stages:
                    cows: 123
                """), Intensity.HARSH);
        assertEquals(123, s.cows());
    }

    @Test
    void amountsAreClampedAndHoppersKeptInWholeLoops() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("""
                benchmark:
                  stages:
                    villagers:
                      MEDIUM: 99999
                    hoppers:
                      MEDIUM: 1002
                """), null);
        assertEquals(3000, s.villagers());
        assertEquals(1000, s.hoppers());
    }

    @Test
    void extremeGoesWellPastHarsh() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml(""), Intensity.EXTREME);
        assertEquals(1000, s.villagers());
        assertEquals(400, s.clocks());
        assertEquals(6000, s.hoppers());
        assertEquals(4000, s.items());
        assertEquals(800, s.minecarts());
        assertEquals(50, s.explosions());
    }

    @Test
    void anUnknownIntensityIsMedium() throws Exception {
        final Benchmark.Settings s = Benchmark.Settings.from(yaml("benchmark:\n  intensity: nonsense\n"), null);
        assertEquals(Intensity.MEDIUM, s.intensity());
        assertEquals(100, s.villagers());
    }
}

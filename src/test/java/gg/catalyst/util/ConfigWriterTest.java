// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigWriterTest {
    @Test
    void keepsCommentsAndChangesOnlyTheValue(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("config.yml");

        try (InputStream in = ConfigWriterTest.class.getResourceAsStream("/config.yml")) {
            Files.copy(in, file);
        }
        final List<String> before = Files.readAllLines(file);
        final long commentsBefore = before.stream().filter(l -> l.trim().startsWith("#")).count();

        ConfigWriter.set(file.toFile(), "runtime.mob-limiter.enabled", false);

        final List<String> after = Files.readAllLines(file);
        assertEquals(commentsBefore, after.stream().filter(l -> l.trim().startsWith("#")).count());
        assertFalse(YamlConfiguration.loadConfiguration(file.toFile()).getBoolean("runtime.mob-limiter.enabled", true));
        assertEquals(YamlConfiguration.loadConfiguration(file.toFile()).getInt("runtime.mob-limiter.max-mobs-per-chunk"),
                40);
    }

    @Test
    void oldDefaultStageNumbersGiveWayToTheLevelsAndChosenOnesStay(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                # kept
                benchmark:
                  repeats: 3
                  stages:
                    villagers: 100 # the old default
                    cows: 350 # chosen
                    hoppers: default
                    pistons:
                      HARSH: 90
                """);

        final ConfigWriter.StageUpdate update = ConfigWriter.updateBenchStages(file.toFile());

        assertEquals(List.of("villagers", "hoppers"), update.updated());
        assertEquals(List.of("cows"), update.fixed());
        final YamlConfiguration after = YamlConfiguration.loadConfiguration(file.toFile());
        assertFalse(after.contains("benchmark.stages.villagers"));
        assertFalse(after.contains("benchmark.stages.hoppers"));
        assertEquals(350, after.getInt("benchmark.stages.cows"));
        assertEquals(90, after.getInt("benchmark.stages.pistons.HARSH"));
        assertTrue(Files.readString(file).contains("# kept"));
    }

    @Test
    void addsMissingOptionsButKeepsEverythingAlreadyThere(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("config.yml");
        // A partial config: a changed value, a hand-added key the schema does not have, and a comment.
        Files.writeString(file, """
                # my note
                scan-on-startup: false
                benchmark:
                  repeats: 5
                  my-custom-key: keep-me
                """);

        final List<String> added;
        try (InputStream def = ConfigWriterTest.class.getResourceAsStream("/config.yml")) {
            added = ConfigWriter.addMissingDefaults(file.toFile(), def);
        }
        final YamlConfiguration after = YamlConfiguration.loadConfiguration(file.toFile());

        // Nothing already there is touched: kept the changed value, and a key not in the defaults.
        assertFalse(after.getBoolean("scan-on-startup", true), "user value was overwritten");
        assertEquals(5, after.getInt("benchmark.repeats"), "user value was reset to the default");
        assertEquals("keep-me", after.getString("benchmark.my-custom-key"), "a hand-added key was pruned");
        assertTrue(Files.readString(file).contains("# my note"), "a comment was lost");

        // A new option is filled in from the defaults, with its explaining comment.
        assertTrue(added.contains("benchmark.tools-during-run"), "new option not reported as added");
        assertFalse(after.getBoolean("benchmark.tools-during-run", true), "new option not added with its default");
        assertTrue(Files.readString(file).contains("perf lag and perf network are refused"), "new option's comment was not carried");
    }

    @Test
    void addingNothingLeavesAnUpToDateConfigAlone(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("config.yml");

        try (InputStream in = ConfigWriterTest.class.getResourceAsStream("/config.yml")) {
            Files.copy(in, file);
        }
        final long before = Files.getLastModifiedTime(file).toMillis();
        Thread.sleep(20);

        try (InputStream def = ConfigWriterTest.class.getResourceAsStream("/config.yml")) {
            assertTrue(ConfigWriter.addMissingDefaults(file.toFile(), def).isEmpty());
        }
        assertEquals(before, Files.getLastModifiedTime(file).toMillis(), "an up-to-date config was rewritten");
    }

    @Test
    void aConfigAlreadyUpToDateIsNotRewritten(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "benchmark:\n  stages:\n    cows: 350\n");
        final long before = Files.getLastModifiedTime(file).toMillis();
        Thread.sleep(20);
        assertTrue(ConfigWriter.updateBenchStages(file.toFile()).updated().isEmpty());
        assertEquals(before, Files.getLastModifiedTime(file).toMillis());
    }
}

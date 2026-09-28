// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.Block;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.route.Route;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Changes single values in a YAML file while keeping its comments and layout. */
public final class ConfigWriter {
    private ConfigWriter() {}

    public static void set(File file, String path, Object value) throws IOException {
        final YamlDocument doc = load(file);
        doc.set(path, value);
        doc.save(file);
    }

    /**
     * Adds keys a newer default has but the file on disk lacks, so new options appear on upgrade
     * without regenerating. Purely additive - it never changes or removes what is already there,
     * so hand-set values (endpoints, keys, custom headers) stay. Returns the paths it added.
     */
    public static List<String> addMissingDefaults(File file, InputStream defaults) throws IOException {
        final YamlDocument doc = load(file);
        final YamlDocument def;

        try (InputStream in = defaults) {
            def = YamlDocument.create(in);
        }
        final List<String> added = new ArrayList<>();

        for (final String path : def.getRoutesAsStrings(true)) {
            if (doc.contains(path) || def.get(path) instanceof Section) continue;

            doc.set(path, def.get(path));
            added.add(path);

            // Best-effort: carry the default's comment onto the new key. Never fatal if it cannot.
            try {
                final Route route = Route.fromString(path);
                final Block<?> from = def.getOptionalBlock(route).orElse(null);

                if (from != null) doc.getOptionalBlock(route).ifPresent(to -> to.setComments(from.getComments()));
            } catch (Throwable ignored) {
                // The value is in; only its comment was missed.
            }
        }

        if (!added.isEmpty()) doc.save(file);

        return added;
    }

    /** The amount each benchmark stage had, as one number, before stages had levels. */
    private static final Map<String, Integer> OLD_STAGE_DEFAULTS = Map.of(
            "villagers", 100, "cows", 200, "redstone-clocks", 50, "pistons", 50, "hoppers", 1000,
            "dropped-items", 500, "player-footprints", 3, "chunk-generation", 30);

    /** What an update of the benchmark stages did: which were brought up to date, which were kept. */
    public record StageUpdate(List<String> updated, List<String> fixed) {}

    /**
     * Updates pre-levels bench stages: an old default number (or a word) is removed so the
     * built-in levels apply; a number someone chose stays. Comments are kept.
     */
    public static StageUpdate updateBenchStages(File file) throws IOException {
        final YamlDocument doc = load(file);
        final Section stages = doc.getOptionalSection("benchmark.stages").orElse(null);
        final List<String> updated = new ArrayList<>(), fixed = new ArrayList<>();

        if (stages == null) return new StageUpdate(updated, fixed);

        for (final Object key : new ArrayList<>(stages.getKeys())) {
            final String name = key.toString();
            final Object value = stages.get(name);

            if (value instanceof Section) continue;
            final Integer old = OLD_STAGE_DEFAULTS.get(name);

            if (value instanceof String || value instanceof Number n && old != null && n.intValue() == old) {
                stages.remove(name);
                updated.add(name);
            } else if (value instanceof Number) {
                fixed.add(name);
            }
        }

        if (updated.isEmpty()) return new StageUpdate(updated, fixed);

        if (stages.getKeys().isEmpty()) doc.remove("benchmark.stages");
        doc.save(file);

        return new StageUpdate(updated, fixed);
    }

    private static YamlDocument load(File file) throws IOException {
        // create(File) leaves its stream open, which locks the file on Windows.
        try (InputStream in = new FileInputStream(file)) {
            return YamlDocument.create(in);
        }
    }
}

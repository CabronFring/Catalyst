// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Which loaded world is the server's real survival world, so a world grown from its seed can
 * time chunk generation. The default world is often a void or flat hub, so this picks the
 * overworld with the most region data on disk, unless benchmark.terrain-world names one.
 */
final class RealWorld {
    /** One loaded world, as far as the choice is concerned. */
    record Candidate(String name, boolean overworld, boolean bench, long regionBytes) {}

    /** The pick and the reason, for the report. */
    record Choice(String name, String why) {}

    private RealWorld() {}

    /**
     * @param configured benchmark.terrain-world, or blank for automatic
     * @return the choice, or null with nothing suitable (then {@code refusal} explains)
     */
    static Choice choose(List<Candidate> worlds, String configured) {
        if (configured != null && !configured.isBlank()) {
            for (final Candidate c : worlds)
                if (c.name().equalsIgnoreCase(configured.trim()) && !c.bench())
                    return new Choice(c.name(), "set in benchmark.terrain-world");
            return null;
        }
        final Candidate best = worlds.stream()
                .filter(c -> c.overworld() && !c.bench())
                .max(Comparator.comparingLong(Candidate::regionBytes))
                .orElse(null);

        if (best == null) return null;

        return new Choice(best.name(), "the overworld with the most generated land, "
                + size(best.regionBytes()) + " of region files");
    }

    /** Why nothing was chosen, for when {@link #choose} returns null. */
    static String refusal(String configured) {
        return configured != null && !configured.isBlank()
                ? "benchmark.terrain-world names \"" + configured.trim() + "\", which is not a loaded world"
                : "no overworld is loaded to copy terrain from";
    }

    /** The loaded worlds as candidates. Reads the disk, so call it off the server thread if it matters. */
    static List<Candidate> loaded() {
        final List<Candidate> out = new ArrayList<>();

        for (final World w : Bukkit.getWorlds())
            out.add(new Candidate(w.getName(), w.getEnvironment() == World.Environment.NORMAL,
                    Benchmark.isBenchWorld(w.getName()), regionBytes(w.getWorldFolder())));

        return out;
    }

    /** The region files of one world: the terrain it has generated, on either save layout. */
    private static long regionBytes(File worldFolder) {
        final File[] files = new File(worldFolder, "region").listFiles((d, n) -> n.endsWith(".mca"));
        long total = 0;

        if (files != null) for (final File f : files) total += f.length();

        return total;
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));

        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));

        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }
}

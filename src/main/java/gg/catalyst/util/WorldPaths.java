// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where worlds and player files live on disk, which Minecraft 26 changed: the main save's
 * dimensions moved to world/dimensions/minecraft/<name>, extra worlds became dimensions of
 * the main save, and player files moved to world/players/. getWorlds() has no promised
 * order, so every loaded world's save is considered.
 */
public final class WorldPaths {
    private WorldPaths() {}

    /** The save of every loaded world, without duplicates. The main save is always among them. */
    public static Set<File> saveRoots() {
        final Set<File> roots = new LinkedHashSet<>();

        for (final World world : Bukkit.getWorlds()) roots.add(saveRootOf(world));

        return roots;
    }

    /** The save a loaded world is written into: itself on 1.21, the main save on 26. */
    public static File saveRootOf(World world) {
        return saveRoot(world.getWorldFolder());
    }

    /** From a dimension folder to its save: .../world/dimensions/minecraft/overworld to .../world. */
    static File saveRoot(File worldFolder) {
        final File namespace = worldFolder.getParentFile();
        final File dimensions = namespace == null ? null : namespace.getParentFile();

        if (dimensions != null && dimensions.getName().equals("dimensions"))
            return dimensions.getParentFile();

        return worldFolder;
    }

    /**
     * Every place a world with this name may have been written: its own folder in the world
     * container (1.21 and earlier), and a dimension of a loaded save (26 and later).
     */
    public static Set<File> candidates(String worldName) {
        final Set<File> found = new LinkedHashSet<>();
        found.add(new File(Bukkit.getWorldContainer(), worldName));

        for (final File root : saveRoots()) found.add(new File(root, "dimensions/minecraft/" + worldName));

        return found;
    }
}

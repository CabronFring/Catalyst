// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.function.Consumer;

/**
 * The Paper-only world call the benchmark uses, kept apart so Spigot and CraftBukkit never
 * load a class that uses it. Callers check {@link #available()} first.
 */
final class PaperWorlds {
    private PaperWorlds() {}

    static boolean available() {
        try {
            World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
            return true;
        } catch (NoSuchMethodException | LinkageError notPaper) {
            return false;
        }
    }

    /**
     * Loads (generating if new) a chunk the way a player's view does on Paper: off the server
     * thread, with nothing kept loaded afterwards. {@code done} runs on the server thread.
     */
    static void generate(World world, int x, int z, Consumer<Chunk> done) {
        world.getChunkAtAsync(x, z, true).thenAccept(done);
    }
}

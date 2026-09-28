// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.UUID;

/**
 * Identifies a chunk without holding a reference to it. Keeping Chunk objects alive
 * in a map pins them in memory and can stop them unloading.
 */
public record ChunkKey(UUID world, int x, int z) {
    public static ChunkKey of(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    /** Derives the key from block coordinates without touching (or loading) the chunk. */
    public static ChunkKey ofBlock(World world, int blockX, int blockZ) {
        return new ChunkKey(world.getUID(), blockX >> 4, blockZ >> 4);
    }

    @Override
    public String toString() {
        return x + ", " + z;
    }
}

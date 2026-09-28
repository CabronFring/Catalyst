// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.lag;

import gg.catalyst.util.ChunkKey;

/** One chunk's event counts over a sampling window. */
public record Hotspot(ChunkKey chunk, String worldName, long physics, long fluid, long redstone) {
    public long total() {
        return physics + fluid + redstone;
    }

    /** Chunk coordinates, as F3 shows them. */
    public int chunkX() { return chunk.x(); }
    public int chunkZ() { return chunk.z(); }

    /**
     * Block coordinates at the middle of the chunk, which is where you actually want to
     * land. The corner sits on a chunk boundary and can drop you in the neighbour.
     */
    public int blockX() { return (chunk.x() << 4) + 8; }
    public int blockZ() { return (chunk.z() << 4) + 8; }

    /** The event type contributing most here, to hint at what is actually running. */
    public String dominantCause() {
        if (redstone >= physics && redstone >= fluid) return "redstone";

        if (fluid >= physics) return "liquid flow";

        return "block physics";
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

import java.util.Set;

/**
 * A real world as the roaming bots' pathfinder sees it, read on the server thread. Only
 * chunks already loaded are read, so planning a route never loads or generates one; an
 * unloaded block counts as somewhere nobody can go.
 *
 * Besides what a player simply cannot walk through, it keeps the bots off anything their
 * walking would change in someone's world - farmland they would trample, pressure plates,
 * tripwires and sculk that would fire redstone, turtle eggs - and off anything that hurts.
 */
final class WorldTerrain implements RoamPaths.Terrain {
    /** Blocks not to stand on: trampled, set off, harmful, or bouncing the bot off its route. */
    private static final Set<String> BAD_GROUND = Set.of(
            "FARMLAND", "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE", "SLIME_BLOCK", "HONEY_BLOCK",
            "TURTLE_EGG", "SNIFFER_EGG", "POINTED_DRIPSTONE", "BIG_DRIPLEAF", "SCULK_SENSOR",
            "CALIBRATED_SCULK_SENSOR", "SCULK_SHRIEKER", "TNT", "SCAFFOLDING", "HOPPER");
    /** Blocks not to walk through: they fire redstone, hurt, trap, or lead somewhere else. */
    private static final Set<String> BAD_SPACE = Set.of(
            "FIRE", "SOUL_FIRE", "SWEET_BERRY_BUSH", "COBWEB", "POWDER_SNOW", "TRIPWIRE", "TRIPWIRE_HOOK",
            "WITHER_ROSE", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY", "POINTED_DRIPSTONE", "TURTLE_EGG",
            "SNIFFER_EGG", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "SCULK_SHRIEKER", "CAMPFIRE",
            "SOUL_CAMPFIRE", "LIGHT");
    /** Always under water, though not water blocks themselves. */
    private static final Set<String> WATER_PLANTS = Set.of(
            "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS", "BUBBLE_COLUMN");

    private final World world;

    WorldTerrain(World world) {
        this.world = world;
    }

    private boolean loaded(int x, int z) {
        return this.world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public double standAt(int x, int y, int z) {
        if (!this.loaded(x, z) || y - 1 < this.world.getMinHeight() || y + 1 >= this.world.getMaxHeight()) return Double.NaN;
        final Block below = this.world.getBlockAt(x, y - 1, z);
        final Material type = below.getType();

        if (!type.isSolid() || BAD_GROUND.contains(type.name()) || type.name().endsWith("_PRESSURE_PLATE")) return Double.NaN;

        // Feet rest on the top of the block below: a full block's top is y, a slab's y - 0.5.
        // Anything lower is too thin to be the ground, anything higher (a fence) is a wall.
        final double top = below.getBoundingBox().getMaxY();

        if (top < y - 0.5 || top > y + 1e-6) return Double.NaN;

        // The bot's own space must be clear of water too: standing on a lake floor with water at
        // the feet looks like walking on the surface (open() rejects liquids), so route round it.
        return this.open(x, y, z) && this.open(x, y + 1, z) ? top : Double.NaN;
    }

    @Override
    public boolean open(int x, int y, int z) {
        if (!this.loaded(x, z) || y < this.world.getMinHeight() || y >= this.world.getMaxHeight()) return false;
        final Block b = this.world.getBlockAt(x, y, z);
        final String type = b.getType().name();

        // isLiquid is only water and lava blocks themselves: kelp, seagrass, bubble columns and
        // waterlogged blocks are water too, and a bot cannot swim.
        if (b.isLiquid() || WATER_PLANTS.contains(type) || b.getBlockData() instanceof Waterlogged w && w.isWaterlogged())
            return false;

        return b.isPassable() && !BAD_SPACE.contains(type) && !type.endsWith("_PRESSURE_PLATE");
    }
}

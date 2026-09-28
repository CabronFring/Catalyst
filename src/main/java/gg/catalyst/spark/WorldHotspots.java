// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Finds where in the world a profile's cost comes from. A profile knows what kind of work the tick
 * did but not where; pairing "mob AI is most of your tick" with "this chunk holds 150 villagers" is
 * what makes it actionable. Runs on the server thread, since it reads loaded chunks.
 */
public final class WorldHotspots {
    public record Spot(String world, int chunkX, int chunkZ, int count, String what) {
        public int blockX() { return (chunkX << 4) + 8; }
        public int blockZ() { return (chunkZ << 4) + 8; }
    }

    private WorldHotspots() {}

    /** Chunks with the most living non-player entities, naming the most common type. */
    public static List<Spot> crowdedChunks(Plugin plugin, int limit) {
        final List<Spot> out = new ArrayList<>();

        for (final World world : plugin.getServer().getWorlds())
            for (final Chunk chunk : world.getLoadedChunks()) {
                final Spot spot = crowdedSpot(chunk);

                if (spot != null) out.add(spot);
            }

        return top(out, limit);
    }

    /** Chunks with the most ticking block entities of a given kind, such as hoppers. */
    public static List<Spot> blockEntityChunks(Plugin plugin, String typeFragment, int limit) {
        final List<Spot> out = new ArrayList<>();

        for (final World world : plugin.getServer().getWorlds())
            for (final Chunk chunk : world.getLoadedChunks()) {
                final Spot spot = blockEntitySpot(chunk, typeFragment);

                if (spot != null) out.add(spot);
            }

        return top(out, limit);
    }

    /** One chunk's mobs, or null when it is not crowded. Must run where the chunk may be read. */
    static Spot crowdedSpot(Chunk chunk) {
        final Map<EntityType, Integer> types = new EnumMap<>(EntityType.class);
        int mobs = 0;

        for (final Entity entity : chunk.getEntities()) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player) continue;
            mobs++;
            types.merge(entity.getType(), 1, Integer::sum);
        }

        return mobs < 8 ? null : new Spot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), mobs, describe(types));
    }

    /** One chunk's block entities of a kind, or null when there are few. Must run where the chunk may be read. */
    static Spot blockEntitySpot(Chunk chunk, String typeFragment) {
        int count = 0;

        for (final BlockState state : chunk.getTileEntities())
            if (state.getType().name().contains(typeFragment)) count++;

        return count < 8 ? null
                : new Spot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), count, typeFragment.toLowerCase() + "s");
    }

    static List<Spot> top(List<Spot> spots, int limit) {
        final List<Spot> sorted = new ArrayList<>(spots);
        sorted.sort((a, b) -> Integer.compare(b.count(), a.count()));

        return sorted.size() > limit ? new ArrayList<>(sorted.subList(0, limit)) : sorted;
    }

    private static String describe(Map<EntityType, Integer> types) {
        Map.Entry<EntityType, Integer> top = null;

        for (final Map.Entry<EntityType, Integer> e : types.entrySet())
            if (top == null || e.getValue() > top.getValue()) top = e;

        if (top == null) return "mobs";
        final String name = top.getKey().name().toLowerCase().replace('_', ' ');

        return types.size() == 1 ? name + "s" : "mostly " + top.getValue() + " " + name + "s";
    }

    /** Plugin package prefixes, as a fallback when spark leaves a class unattributed. */
    public static Map<String, String> pluginPackages(Plugin self) {
        final Map<String, String> out = new HashMap<>();

        for (final Plugin p : self.getServer().getPluginManager().getPlugins()) {
            final String main = p.getClass().getName();
            final int dot = main.lastIndexOf('.');

            if (dot > 0) out.put(main.substring(0, dot), p.getName());
        }

        return out;
    }
}

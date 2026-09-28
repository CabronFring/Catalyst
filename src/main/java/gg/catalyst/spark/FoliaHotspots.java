// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * WorldHotspots for Folia, where a chunk can only be read on its region's thread and there is no
 * safe way to list every loaded chunk from outside. A task scheduled on each online player runs on
 * the thread that owns them, so this checks the chunks around every player; chunks loaded with
 * nobody near them go unseen, and the report says so. Its own class so it never loads without Folia.
 */
public final class FoliaHotspots {
    /** Chunks checked in every direction around each player. */
    private static final int RADIUS = 4;
    private static final long TIMEOUT_MILLIS = 5000;

    private FoliaHotspots() {}

    /**
     * Scans around every online player on their own region's thread, then hands the busiest
     * crowded and hopper chunks to {@code done}, on whichever thread finishes last. If some
     * region is too stuck to answer, what was gathered is reported after a timeout.
     */
    public static void aroundPlayers(Plugin plugin, int limit, BiConsumer<List<WorldHotspots.Spot>, List<WorldHotspots.Spot>> done) {
        final Map<String, WorldHotspots.Spot> crowded = new ConcurrentHashMap<>();
        final Map<String, WorldHotspots.Spot> hoppers = new ConcurrentHashMap<>();
        final List<? extends Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        final AtomicInteger pending = new AtomicInteger(players.size());
        final AtomicBoolean reported = new AtomicBoolean();

        final Runnable finish = () -> {
            if (reported.compareAndSet(false, true))
                done.accept(WorldHotspots.top(List.copyOf(crowded.values()), limit),
                        WorldHotspots.top(List.copyOf(hoppers.values()), limit));
        };

        if (players.isEmpty()) {
            finish.run();
            return;
        }

        for (final Player player : players) {
            final Runnable countDown = () -> {
                if (pending.decrementAndGet() == 0) finish.run();
            };
            final boolean scheduled = player.getScheduler().run(plugin, task -> {
                try {
                    scan(player, crowded, hoppers);
                } finally {
                    countDown.run();
                }
            }, countDown) != null; // null: the player already left, and "retired" will not run

            if (!scheduled) countDown.run();
        }
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> finish.run(),
                TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void scan(Player player, Map<String, WorldHotspots.Spot> crowded, Map<String, WorldHotspots.Spot> hoppers) {
        final World world = player.getWorld();
        final int cx = player.getLocation().getBlockX() >> 4, cz = player.getLocation().getBlockZ() >> 4;

        for (int x = cx - RADIUS; x <= cx + RADIUS; x++) {
            for (int z = cz - RADIUS; z <= cz + RADIUS; z++) {
                // Near the edge of a region a neighbouring chunk can belong to another one.
                if (!world.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(world, x, z)) continue;
                final Chunk chunk = world.getChunkAt(x, z);
                final String key = world.getName() + ':' + x + ':' + z;
                final WorldHotspots.Spot mobs = WorldHotspots.crowdedSpot(chunk);

                if (mobs != null) crowded.put(key, mobs);
                final WorldHotspots.Spot hop = WorldHotspots.blockEntitySpot(chunk, "HOPPER");

                if (hop != null) hoppers.put(key, hop);
            }
        }
    }
}

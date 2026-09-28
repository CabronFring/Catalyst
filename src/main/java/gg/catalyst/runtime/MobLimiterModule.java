// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.runtime;

import gg.catalyst.util.ChunkKey;
import org.bukkit.Chunk;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Stops naturally spawning mobs from piling up in a single chunk, which is the usual
 * shape of a runaway mob farm. Only natural and spawner spawns are limited - anything
 * a player deliberately caused (breeding, spawn eggs, plugins) always goes through.
 */
public final class MobLimiterModule implements RuntimeModule {
    private static final long CACHE_TTL_MILLIS = 1000L;

    private record Count(int mobs, long takenAt) {}

    private final Map<ChunkKey, Count> cache = new ConcurrentHashMap<>();
    private final LongAdder blocked = new LongAdder();

    // Volatile: toggled from the command thread, read on Folia's region threads.
    private volatile boolean enabled;
    private volatile int maxPerChunk;

    @Override public String id() { return "mob-limiter"; }
    @Override public String name() { return "Mob Limiter"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Caps how many mobs can naturally spawn in one chunk, so a single overloaded "
             + "farm cannot drag down the whole server.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("runtime.mob-limiter.enabled", true);
        this.maxPerChunk = cfg.getInt("runtime.mob-limiter.max-mobs-per-chunk", 40);
        this.cache.clear();
    }

    @Override
    public String status() {
        return "cap " + this.maxPerChunk + " per chunk, " + this.blocked.sum() + " spawn(s) declined";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!this.enabled) return;

        if (!isAmbientSpawn(event.getSpawnReason())) return;

        final Chunk chunk = event.getLocation().getChunk();

        if (this.countMobs(chunk) < this.maxPerChunk) return;

        event.setCancelled(true);
        this.blocked.increment();
        this.cache.remove(ChunkKey.of(chunk));
    }

    /** Only spawns the world produced on its own - never something a player asked for. */
    private static boolean isAmbientSpawn(CreatureSpawnEvent.SpawnReason reason) {
        return reason == CreatureSpawnEvent.SpawnReason.NATURAL
            || reason == CreatureSpawnEvent.SpawnReason.SPAWNER
            || reason == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS;
    }

    /**
     * Scanning a chunk's entity array on every spawn is wasteful during a burst, so the
     * result is reused for a second. Being briefly stale only shifts the cap by a few mobs.
     */
    private int countMobs(Chunk chunk) {
        final ChunkKey key = ChunkKey.of(chunk);
        final long now = System.currentTimeMillis();

        final Count cached = this.cache.get(key);

        if (cached != null && now - cached.takenAt() < CACHE_TTL_MILLIS) return cached.mobs();

        int mobs = 0;

        for (final Entity entity : chunk.getEntities())
            if (entity instanceof LivingEntity && !(entity instanceof Player)) mobs++;

        this.cache.put(key, new Count(mobs, now));

        return mobs;
    }
}

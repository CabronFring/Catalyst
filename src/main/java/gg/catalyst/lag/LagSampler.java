// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.lag;

import gg.catalyst.util.ChunkKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts the events that usually mean "a contraption is eating the tick", grouped by chunk, so an
 * admin learns where the problem physically is. Registered only while sampling: BlockPhysicsEvent is
 * one of the hottest events in the game, so a permanent listener would itself cost. Counting happens
 * inside the event on whichever thread owns that region, which makes it safe on Folia unscheduled.
 */
public final class LagSampler implements Listener {
    private static final class Counts {
        final LongAdder physics = new LongAdder();
        final LongAdder fluid = new LongAdder();
        final LongAdder redstone = new LongAdder();
    }

    private final Map<ChunkKey, Counts> counts = new ConcurrentHashMap<>();
    private volatile boolean active = false;
    private long startedAt = 0L;

    public boolean isActive() { return this.active; }

    public void start(Plugin plugin) {
        this.counts.clear();
        this.startedAt = System.currentTimeMillis();
        this.active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        this.active = false;
        HandlerList.unregisterAll(this);
    }

    public long elapsedSeconds() {
        return Math.max(1, (System.currentTimeMillis() - this.startedAt) / 1000);
    }

    // Monitor priority: observe what actually happened without influencing it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (this.active) this.record(event.getBlock()).physics.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        if (this.active) this.record(event.getBlock()).fluid.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent event) {
        if (this.active) this.record(event.getBlock()).redstone.increment();
    }

    /**
     * Keys off block coordinates rather than Block#getChunk, which can load a chunk
     * that was not otherwise loaded - a lag tool must not create work.
     */
    private Counts record(Block block) {
        final World world = block.getWorld();
        final ChunkKey key = ChunkKey.ofBlock(world, block.getX(), block.getZ());

        return this.counts.computeIfAbsent(key, k -> new Counts());
    }

    /** Busiest chunk first. */
    public List<Hotspot> results() {
        final List<Hotspot> out = new ArrayList<>();

        for (final Map.Entry<ChunkKey, Counts> entry : this.counts.entrySet()) {
            final ChunkKey key = entry.getKey();
            final Counts c = entry.getValue();

            final World world = Bukkit.getWorld(key.world());
            final String worldName = world == null ? "unknown" : world.getName();

            final Hotspot hotspot = new Hotspot(key, worldName,
                    c.physics.sum(), c.fluid.sum(), c.redstone.sum());

            if (hotspot.total() > 0) out.add(hotspot);
        }
        out.sort((a, b) -> Long.compare(b.total(), a.total()));

        return out;
    }
}

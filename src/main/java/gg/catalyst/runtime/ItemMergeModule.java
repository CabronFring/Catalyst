// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.runtime;

import gg.catalyst.bench.Benchmark;
import org.bukkit.Chunk;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.atomic.LongAdder;

/**
 * Collapses ground items into existing stacks once a chunk is carrying too many item
 * entities (mass mining, mob grinders). Never deletes items: a spawn is only cancelled after
 * its contents were added to another stack.
 */
public final class ItemMergeModule implements RuntimeModule {
    private final LongAdder merged = new LongAdder();

    // Volatile: toggled from the command thread, read on Folia's region threads.
    private volatile boolean enabled;
    private volatile int threshold;

    @Override public String id() { return "item-merger"; }
    @Override public String name() { return "Item Merger"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Merges dropped items into existing stacks once a chunk holds more than the "
             + "threshold, cutting entity count without ever destroying items.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("runtime.item-merger.enabled", true);
        this.threshold = cfg.getInt("runtime.item-merger.items-per-chunk-threshold", 20);
    }

    @Override
    public String status() {
        return "threshold " + this.threshold + " per chunk, " + this.merged.sum() + " stack(s) merged";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!this.enabled) return;

        // The benchmark's dropped-items stage measures items that stay apart; merging them would
        // measure nothing.
        if (Benchmark.isBenchWorld(event.getEntity().getWorld().getName())) return;

        final Item spawning = event.getEntity();
        final Chunk chunk = spawning.getLocation().getChunk();
        final ItemStack incoming = spawning.getItemStack();

        final Item target = this.findMergeTarget(chunk, spawning, incoming);

        if (target == null) return;

        final ItemStack existing = target.getItemStack();
        existing.setAmount(existing.getAmount() + incoming.getAmount());
        target.setItemStack(existing);

        event.setCancelled(true);
        this.merged.increment();
    }

    /**
     * Returns a stack that can absorb the incoming one without exceeding its max size,
     * or null if the chunk is under threshold or nothing matches.
     */
    private Item findMergeTarget(Chunk chunk, Item spawning, ItemStack incoming) {
        int items = 0;
        Item candidate = null;

        for (final Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Item other)) continue;
            items++;

            if (candidate != null || other == spawning) continue;

            final ItemStack stack = other.getItemStack();

            if (!stack.isSimilar(incoming)) continue;

            if (stack.getAmount() + incoming.getAmount() > stack.getMaxStackSize()) continue;
            candidate = other;
        }

        return items >= this.threshold ? candidate : null;
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import gg.catalyst.runtime.RuntimeModule;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Creative mode lets clients push arbitrary item stacks to the server. A modified client
 * can craft items with display names, lore, or enchantment lists far beyond what the
 * vanilla client would ever produce, which can cause lag when those items are serialized,
 * broadcast, or opened in an inventory.
 */
public final class CreativeNbtGuard implements RuntimeModule {
    private final LongAdder blocked = new LongAdder();

    private volatile boolean enabled;
    private volatile int maxDisplayNameLength;
    private volatile int maxLoreLines;
    private volatile int maxEnchantments;

    @Override public String id() { return "creative-nbt"; }
    @Override public String name() { return "Creative NBT Guard"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Cancels creative-mode item placements whose display name, lore, or enchantment "
             + "count exceeds the configured limits, blocking crafted NBT payloads.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("protection.creative-nbt.enabled", false);
        this.maxDisplayNameLength = Math.max(1, cfg.getInt("protection.creative-nbt.max-display-name-length", 100));
        this.maxLoreLines = Math.max(1, cfg.getInt("protection.creative-nbt.max-lore-lines", 50));
        this.maxEnchantments = Math.max(1, cfg.getInt("protection.creative-nbt.max-enchantments", 50));
    }

    @Override
    public String status() {
        return "name " + this.maxDisplayNameLength + " / lore " + this.maxLoreLines
             + " / enchants " + this.maxEnchantments + ", " + this.blocked.sum() + " item(s) blocked";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreativeSlot(InventoryCreativeEvent event) {
        if (!this.enabled) return;

        if (event.getWhoClicked().getGameMode() != GameMode.CREATIVE) return;

        final ItemStack item = event.getCursor();

        if (item == null || item.getType().isAir()) return;

        final ItemMeta meta = item.getItemMeta();

        if (meta == null) return;

        if (meta.hasDisplayName() && meta.getDisplayName().length() > this.maxDisplayNameLength) {
            event.setCancelled(true);
            this.blocked.increment();
            return;
        }

        final List<String> lore = meta.getLore();

        if (lore != null && lore.size() > this.maxLoreLines) {
            event.setCancelled(true);
            this.blocked.increment();
            return;
        }

        if (meta.getEnchants().size() > this.maxEnchantments) {
            event.setCancelled(true);
            this.blocked.increment();
        }
    }
}

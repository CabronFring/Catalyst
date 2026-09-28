// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import gg.catalyst.runtime.RuntimeModule;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.PrepareAnvilEvent;

import java.util.concurrent.atomic.LongAdder;

/**
 * The vanilla client caps anvil renames at 50 characters, but modified clients can send
 * longer names. Items with extremely long display names can cause rendering or processing
 * issues on clients that receive them.
 */
public final class AnvilRenameGuard implements RuntimeModule {
    private final LongAdder blocked = new LongAdder();

    private volatile boolean enabled;
    private volatile int maxNameLength;

    @Override public String id() { return "anvil-rename"; }
    @Override public String name() { return "Anvil Rename Guard"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Clears the result of an anvil operation whose rename text exceeds the configured "
             + "length, preventing items with oversized display names from being created.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("protection.anvil-rename.enabled", false);
        this.maxNameLength = Math.max(1, cfg.getInt("protection.anvil-rename.max-name-length", 100));
    }

    @Override
    public String status() {
        return "max " + this.maxNameLength + " chars, " + this.blocked.sum() + " rename(s) blocked";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!this.enabled) return;

        final String name = event.getInventory().getRenameText();

        if (name != null && name.length() > this.maxNameLength) {
            event.setResult(null);
            this.blocked.increment();
        }
    }
}

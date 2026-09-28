// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import gg.catalyst.runtime.RuntimeModule;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;

import java.util.concurrent.atomic.LongAdder;

/**
 * Modified clients can send sign text that exceeds the vanilla 15-character-per-line
 * limit. Pathologically long lines can cause issues in certain sign rendering or
 * serialization paths.
 */
public final class SignOverflowGuard implements RuntimeModule {
    private final LongAdder blocked = new LongAdder();

    private volatile boolean enabled;
    private volatile int maxLineLength;

    @Override public String id() { return "sign-overflow"; }
    @Override public String name() { return "Sign Overflow Guard"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Cancels sign edits whose line text exceeds the configured character cap, "
             + "blocking oversized sign packets from modified clients.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("protection.sign-overflow.enabled", false);
        this.maxLineLength = Math.max(1, cfg.getInt("protection.sign-overflow.max-line-length", 100));
    }

    @Override
    public String status() {
        return "max " + this.maxLineLength + " chars/line, " + this.blocked.sum() + " sign(s) blocked";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        if (!this.enabled) return;

        for (final String line : event.getLines()) {
            if (line != null && line.length() > this.maxLineLength) {
                event.setCancelled(true);
                this.blocked.increment();
                return;
            }
        }
    }
}

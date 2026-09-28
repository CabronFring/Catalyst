// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

/**
 * Checks every login on Spigot and CraftBukkit: a verified bot is recorded, and let past a
 * whitelist, ban or full server. Not used on Paper, which deprecates this event and has
 * PaperBotLoginListener instead.
 */
@SuppressWarnings("deprecation") // deprecated by Paper only
final class BukkitBotLoginListener implements Listener {
    private final BotLoginCheck check;

    BukkitBotLoginListener(BotLoginCheck check) {
        this.check = check;
    }

    // HIGHEST, not MONITOR: this changes the outcome, and runs after the plugins that deny.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (this.check.verify(event.getPlayer().getName(), event.getAddress(), event.getHostname())
                && event.getResult() != PlayerLoginEvent.Result.ALLOWED)
            event.allow();
    }
}

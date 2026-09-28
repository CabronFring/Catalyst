// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit, Spigot and non-Folia Paper: one scheduler, the main thread, so the bot argument is ignored. */
final class LegacyBotScheduler implements BotScheduler {
    @Override
    public Handle repeatWithBot(Plugin plugin, Player bot, Runnable task, long delayTicks, long periodTicks) {
        final BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return t::cancel;
    }

    @Override
    public void laterWithBot(Plugin plugin, Player bot, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void laterGlobal(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }
}

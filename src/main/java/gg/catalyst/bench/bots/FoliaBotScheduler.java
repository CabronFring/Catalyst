// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Folia: each bot is steered on the thread that owns it, so reading its position and its
 * surroundings is safe; the delete after a bot leaves runs on the global region thread, since
 * the entity is gone by then. Only loaded on Folia.
 */
final class FoliaBotScheduler implements BotScheduler {
    @Override
    public Handle repeatWithBot(Plugin plugin, Player bot, Runnable task, long delayTicks, long periodTicks) {
        final ScheduledTask t = bot.getScheduler().runAtFixedRate(plugin, s -> task.run(), null, delayTicks, periodTicks);

        return () -> { if (t != null) t.cancel(); };
    }

    @Override
    public void laterWithBot(Plugin plugin, Player bot, Runnable task, long delayTicks) {
        bot.getScheduler().runDelayed(plugin, s -> task.run(), null, delayTicks);
    }

    @Override
    public void laterGlobal(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, s -> task.run(), delayTicks);
    }
}

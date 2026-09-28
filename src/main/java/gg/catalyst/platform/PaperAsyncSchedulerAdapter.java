// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Paper 1.19.4+ and Folia. Required on Folia, which has no Bukkit scheduler at all.
 * Never loaded on Bukkit or Spigot, where these methods do not exist.
 */
final class PaperAsyncSchedulerAdapter implements SchedulerAdapter {
    @Override
    public void runDelayedAsync(Plugin plugin, Runnable task, long delaySeconds) {
        // The async scheduler rejects a zero or negative delay.
        final long delay = Math.max(1, delaySeconds);
        Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delay, TimeUnit.SECONDS);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    @Override
    public void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Bukkit, Spigot, and Paper builds older than 1.19.4. */
final class LegacySchedulerAdapter implements SchedulerAdapter {
    @Override
    public void runDelayedAsync(Plugin plugin, Runnable task, long delaySeconds) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delaySeconds * 20L);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}

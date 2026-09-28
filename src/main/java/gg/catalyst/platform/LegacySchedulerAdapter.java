// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bukkit, Spigot, and Paper builds older than 1.19.4. */
final class LegacySchedulerAdapter implements SchedulerAdapter {
    // Bukkit's async scheduler delays by server ticks, which stretch under lag - so a "60s" delay
    // could take minutes when the server is struggling (exactly when the lag sampler is used). A
    // real-time timer keeps delaySeconds meaning seconds, matching PaperAsyncSchedulerAdapter.
    // Daemon so it never holds the JVM open; pending tasks check the plugin is still enabled.
    private final ScheduledExecutorService delayPool = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "Catalyst-delayed");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void runDelayedAsync(Plugin plugin, Runnable task, long delaySeconds) {
        this.delayPool.schedule(() -> {
            if (plugin.isEnabled()) task.run();
        }, Math.max(1, delaySeconds), TimeUnit.SECONDS);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public Cancellable runGlobalRepeating(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        final var handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);

        return handle::cancel;
    }

    @Override
    public void runAtLocation(Plugin plugin, org.bukkit.Location location, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}

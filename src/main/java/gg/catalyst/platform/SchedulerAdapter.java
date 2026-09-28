// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

import org.bukkit.plugin.Plugin;

/**
 * Folia removes Bukkit.getScheduler() entirely, and older Bukkit/Spigot have no async
 * scheduler, so the two APIs have no overlap. Each implementation lives in its own class
 * so the JVM never loads - and so never has to verify - the one referencing a method the
 * running server does not have.
 */
public interface SchedulerAdapter {
    void runDelayedAsync(Plugin plugin, Runnable task, long delaySeconds);

    void runAsync(Plugin plugin, Runnable task);

    /**
     * Runs on the thread allowed to touch global server state. On Folia that is the
     * global region thread; everywhere else it is the main thread.
     */
    void runGlobal(Plugin plugin, Runnable task);

    static SchedulerAdapter create() {
        if (PlatformDetector.hasAsyncScheduler()) return new PaperAsyncSchedulerAdapter();

        return new LegacySchedulerAdapter();
    }
}

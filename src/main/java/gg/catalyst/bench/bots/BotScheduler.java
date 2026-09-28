// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import gg.catalyst.platform.PlatformDetector;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The scheduling the bot stage needs, in the two shapes servers offer it: Folia's per-entity and
 * global region schedulers, or the one Bukkit scheduler everywhere else. The Folia implementation
 * is a class of its own so its API is only loaded where it exists.
 */
interface BotScheduler {
    interface Handle { void cancel(); }

    /** Repeats on the thread that owns {@code bot}, until the handle is cancelled or the bot is gone. */
    Handle repeatWithBot(Plugin plugin, Player bot, Runnable task, long delayTicks, long periodTicks);

    /** Runs once, after a delay, on the thread that owns {@code bot}. */
    void laterWithBot(Plugin plugin, Player bot, Runnable task, long delayTicks);

    /** Runs once, after a delay, on the thread allowed to touch global state. */
    void laterGlobal(Plugin plugin, Runnable task, long delayTicks);

    static BotScheduler create() {
        return PlatformDetector.isFolia() ? new FoliaBotScheduler() : new LegacyBotScheduler();
    }
}

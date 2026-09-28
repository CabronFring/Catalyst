// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.report;

import org.bukkit.ChatColor;

/**
 * Turns a raw measurement into good / fair / poor. The thresholds are judgement calls, gathered
 * here rather than scattered through the commands so one number is never healthy in one and
 * alarming in another.
 */
public enum Rating {
    GOOD(ChatColor.GREEN),
    FAIR(ChatColor.YELLOW),
    POOR(ChatColor.RED);

    public final ChatColor color;

    Rating(ChatColor color) {
        this.color = color;
    }

    public String paint(String text) {
        return this.color + text;
    }

    /** 20 is the ceiling. Below 19 is measurable, below 16 is felt by players. */
    public static Rating forTps(double tps) {
        if (tps >= 19.0) return GOOD;

        if (tps >= 16.0) return FAIR;

        return POOR;
    }

    /** Ordinary broadband lands under 100ms; past 250ms combat stops feeling fair. */
    public static Rating forPing(int millis) {
        if (millis < 100) return GOOD;

        if (millis < 250) return FAIR;

        return POOR;
    }

    /** Per-packet handler time. The network thread serves many connections, so the budget is small. */
    public static Rating forHandlerMillis(double millis) {
        if (millis < 0.10) return GOOD;

        if (millis < 0.50) return FAIR;

        return POOR;
    }

    /** Block events per chunk per second. Quiet terrain: a handful. A farm: hundreds. A clock or liquid loop: thousands. */
    public static Rating forChunkEventsPerSecond(double perSecond) {
        if (perSecond < 50) return GOOD;

        if (perSecond < 500) return FAIR;

        return POOR;
    }

    /**
     * How late a network event loop runs a task it was asked to run on time. A healthy loop
     * is within a millisecond or two; ten means every packet on it waits that long.
     */
    public static Rating forLoopDelayMillis(double millis) {
        if (millis < 2) return GOOD;

        if (millis < 10) return FAIR;

        return POOR;
    }

    /** Mob count in one chunk, against the limiter's usual cap. */
    public static Rating forMobsInChunk(int mobs) {
        if (mobs < 20) return GOOD;

        if (mobs < 40) return FAIR;

        return POOR;
    }
}

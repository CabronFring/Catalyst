// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

import gg.catalyst.platform.ServerPlatform;
import org.bukkit.Server;

public interface Optimization {
    /** Unique dot-separated id, e.g. "bukkit.spawn-limits". */
    String id();

    /** Grouping for filtered reports: "network", "entities", "world", "general". */
    default String category() { return "general"; }

    /** Short human-readable name. */
    String name();

    /** One sentence explaining what this setting does. */
    String description();

    OptimizationLevel level();

    boolean appliesTo(ServerPlatform platform);

    /**
     * Returns null if this setting is already optimal.
     * Returns a CheckResult with current/recommended values if it should be changed.
     */
    CheckResult check(OptimizationContext ctx);

    /**
     * Writes the recommended value into the in-memory config held by ctx.
     * The caller is responsible for saving to disk afterwards.
     */
    void apply(OptimizationContext ctx);

    /**
     * The config file this setting is written to, relative to the server root
     * (e.g. "bukkit.yml", "config/paper-world-defaults.yml"), so a report can say exactly
     * where a fix landed. Empty when it does not map to a single file.
     */
    default String configFile() { return ""; }

    /**
     * The key, or the parent section for a setting that writes several keys, within
     * {@link #configFile()} (e.g. "spawn-limits", "misc.redstone-implementation"). Empty when
     * not applicable.
     */
    default String configKey() { return ""; }

    /**
     * True when the running server exposes an API to change this without a restart.
     * Config-only settings (redstone engine, hopper behaviour) return false.
     */
    default boolean canApplyLive() { return false; }

    /**
     * Pushes the recommended value into the live server. Only called when
     * canApplyLive() is true, and always on the main or global region thread.
     */
    default void applyLive(Server server) {}
}

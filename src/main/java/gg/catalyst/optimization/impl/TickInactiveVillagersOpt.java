// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class TickInactiveVillagersOpt implements Optimization {
    private static final String PATH =
            "world-settings.default.entity-activation-range.tick-inactive-villagers";

    @Override public String id() { return "spigot.tick-inactive-villagers"; }
    @Override public String configFile() { return "spigot.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Tick Inactive Villagers"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasSpigotConfig; }

    @Override
    public String description() {
        return "Villager AI (pathfinding, job sites, gossip) is unusually expensive. "
             + "Disabling ticks for villagers no player is near saves a lot on trading halls.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final boolean current = ctx.spigotYml().getBoolean(PATH, true);

        if (!current) return null;

        return CheckResult.of(true, false,
                "Real trade-off: villagers outside activation range freeze entirely. Trade "
              + "restock timers stop, they stop farming, and iron golem farms yield less while "
              + "no player is nearby. Avoid this if players rely on afk villager farms.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        ctx.spigotYml().set(PATH, false);
        ctx.markSpigotYmlDirty();
    }
}

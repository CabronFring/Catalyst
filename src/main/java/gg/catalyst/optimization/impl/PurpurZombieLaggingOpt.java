// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PurpurZombieLaggingOpt implements Optimization {
    private static final String PATH = "world-settings.default.mobs.zombie.aggressive-towards-villager-when-lagging";

    @Override public String id() { return "purpur.zombie-villager-lagging"; }
    @Override public String configFile() { return "purpur.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Zombies Ignore Villagers While Lagging"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPurpurConfig; }

    @Override
    public String description() {
        return "Stops zombies hunting villagers while the server is below Purpur's lagging-threshold, "
             + "so a lag spike near a village does not turn into a pile of pathfinding.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return null;

        if (!ctx.purpurYml().getBoolean(PATH, true)) return null;

        return CheckResult.of(true, false,
                "Only active while TPS is under lagging-threshold (19 by default). Real trade-off: "
              + "zombie villager conversion farms stall whenever the server lags.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return;
        ctx.purpurYml().set(PATH, false);
        ctx.markPurpurYmlDirty();
    }
}

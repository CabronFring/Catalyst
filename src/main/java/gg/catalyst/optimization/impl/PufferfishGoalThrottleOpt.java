// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PufferfishGoalThrottleOpt implements Optimization {
    private static final String PATH = "inactive-goal-selector-throttle";

    @Override public String id() { return "pufferfish.inactive-goal-throttle"; }
    @Override public String configFile() { return "pufferfish.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Inactive Goal Selector Throttle"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPufferfishConfig; }

    @Override
    public String description() {
        return "Mobs outside activation range only pick new AI goals once a second instead of "
             + "every tick they wake up.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPufferfishYml()) return null;

        if (ctx.pufferfishYml().getBoolean(PATH, true)) return null;

        return CheckResult.of(false, true,
                "On by default in Pufferfish. Real trade-off: inactive mobs react more slowly, "
              + "which Pufferfish says may break some farms.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPufferfishYml()) return;
        ctx.pufferfishYml().set(PATH, true);
        ctx.markPufferfishYmlDirty();
    }
}

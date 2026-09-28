// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class LeafPoweredRailsOpt implements Optimization {
    private static final String PATH = "performance.optimized-powered-rails";

    @Override public String id() { return "leaf.powered-rails"; }
    @Override public String configFile() { return "config/leaf-global.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Optimized Powered Rails"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasLeafConfig; }

    @Override
    public String description() {
        return "Replaces the powered rail update logic with a rewritten version Leaf says is about "
             + "four times faster. Long powered rail lines are a common redstone lag source.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasLeafGlobal()) return null;

        if (ctx.leafGlobalYml().getBoolean(PATH, false)) return null;

        return CheckResult.of(false, true,
                "Recommended by Leaf. It is a reimplementation rather than vanilla code, so test "
              + "any rail contraption that depends on exact update order.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasLeafGlobal()) return;
        ctx.leafGlobalYml().set(PATH, true);
        ctx.markLeafGlobalDirty();
    }
}

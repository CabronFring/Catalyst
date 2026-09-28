// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PaperRedstoneOpt implements Optimization {
    private static final String PATH = "misc.redstone-implementation";

    @Override public String id() { return "paper.redstone"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Redstone Implementation"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "ALTERNATE_CURRENT is a rewritten redstone dust algorithm, dramatically faster than "
             + "vanilla. It changes how dust propagates power, so most circuits behave identically "
             + "but some timing- and location-sensitive designs can differ - hence not a safe default.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;
        final String current = ctx.paperWorldDefaultsYml().getString(PATH, "VANILLA");

        if ("ALTERNATE_CURRENT".equalsIgnoreCase(current)) return null;

        return CheckResult.of(current, "ALTERNATE_CURRENT",
                "Trade-off: circuits that rely on vanilla's directional update quirks "
              + "(some 0-tick and locational designs) can behave differently. "
              + "Test any critical farms after enabling.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        ctx.paperWorldDefaultsYml().set(PATH, "ALTERNATE_CURRENT");
        ctx.markPaperWorldDirty();
    }
}

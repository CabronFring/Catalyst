// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PaperPathfindingOpt implements Optimization {
    private static final String PATH = "misc.update-pathfinding-on-block-update";

    @Override public String id() { return "paper.pathfinding"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Pathfinding On Block Update"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "By default every block change forces nearby mobs to recalculate their paths. "
             + "Disabling this lets them repath on their normal schedule instead.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;
        final boolean current = ctx.paperWorldDefaultsYml().getBoolean(PATH, true);

        if (!current) return null;

        return CheckResult.of(true, false,
                "Paper's own documentation notes this improves performance significantly with "
              + "almost no noticeable effect on game mechanics. Mobs simply repath on their "
              + "normal schedule rather than the instant a block changes.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        ctx.paperWorldDefaultsYml().set(PATH, false);
        ctx.markPaperWorldDirty();
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PaperMiscOpt implements Optimization {
    private static final String PATH = "environment.optimize-explosions";

    @Override public String id() { return "paper.optimize-explosions"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Explosion Optimization"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "Paper can cache entity lookups during an explosion instead of recalculating "
             + "them repeatedly, which makes TNT and creepers much cheaper to process.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;

        if (ctx.paperWorldDefaultsYml().getBoolean(PATH, false)) return null;

        return CheckResult.of(false, true,
                "The cached lookup makes explosion damage very slightly less precise than vanilla. "
              + "It is not noticeable in normal play, but TNT-duper and cannon servers may care.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        ctx.paperWorldDefaultsYml().set(PATH, true);
        ctx.markPaperWorldDirty();
    }
}

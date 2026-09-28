// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PaperSpawnerTickRateOpt implements Optimization {
    private static final String PATH = "tick-rates.mob-spawner";

    @Override public String id() { return "paper.mob-spawner-tick-rate"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Mob Spawner Tick Rate"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "How often mob spawners tick to look for spawn space and create mobs. "
             + "Ticking them at half rate roughly halves the work they cost.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;
        final int current = ctx.paperWorldDefaultsYml().getInt(PATH, 1);

        if (current >= 2) return null;

        return CheckResult.of(current, 2,
                "Real trade-off: this is not compensated, so spawner-based farms produce "
              + "correspondingly fewer mobs. Worth it on servers with many spawners running, "
              + "a bad idea on servers where players rely on spawner farm rates.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        ctx.paperWorldDefaultsYml().set(PATH, 2);
        ctx.markPaperWorldDirty();
    }
}

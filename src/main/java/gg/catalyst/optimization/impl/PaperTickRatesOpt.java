// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PaperTickRatesOpt implements Optimization {
    private static final String PATH = "tick-rates.grass-spread";

    @Override public String id() { return "paper.grass-spread"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Grass Spread Rate"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "How often the server tries to spread grass onto nearby dirt. "
             + "It runs every tick by default, which is far more often than anyone can perceive.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;
        final int current = ctx.paperWorldDefaultsYml().getInt(PATH, 1);

        if (current >= 4) return null;

        return CheckResult.of(current, 4,
                "The only visible effect is that grass regrows over dirt roughly four times "
              + "more slowly. Nothing else in the game depends on this rate.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        ctx.paperWorldDefaultsYml().set(PATH, 4);
        ctx.markPaperWorldDirty();
    }
}

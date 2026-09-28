// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PufferfishProjectileOpt implements Optimization {
    private static final String PATH = "projectile.max-loads-per-projectile";
    private static final int DEFAULT = 10;
    private static final int TARGET = 8;

    @Override public String id() { return "pufferfish.projectile-chunk-loads"; }
    @Override public String configFile() { return "pufferfish.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Projectile Chunk Loads"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPufferfishConfig; }

    @Override
    public String description() {
        return "How many chunks one projectile may load in its lifetime before it is removed. "
             + "Arrows and pearls fired into unloaded terrain otherwise keep loading chunks.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPufferfishYml()) return null;
        final int current = ctx.pufferfishYml().getInt(PATH, DEFAULT);

        if (current <= TARGET) return null;

        return CheckResult.of(current, TARGET,
                "Real trade-off: projectiles travelling very far, like long pearl cannons or "
              + "tridents thrown off cliffs, get removed a bit sooner.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPufferfishYml()) return;
        ctx.pufferfishYml().set(PATH, TARGET);
        ctx.markPufferfishYmlDirty();
    }
}

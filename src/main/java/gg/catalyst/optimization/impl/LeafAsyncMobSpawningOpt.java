// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class LeafAsyncMobSpawningOpt implements Optimization {
    private static final String PATH = "async.async-mob-spawning.enabled";

    @Override public String id() { return "leaf.async-mob-spawning"; }
    @Override public String configFile() { return "config/leaf-global.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Async Mob Spawning"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasLeafConfig; }

    @Override
    public String description() {
        return "Moves the expensive maths behind mob spawning off the main thread. Mobs still "
             + "spawn on the main thread, so nothing about spawning changes.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasLeafGlobal()) return null;

        if (ctx.leafGlobalYml().getBoolean(PATH, true)) return null;

        return CheckResult.of(false, true,
                "On by default in Leaf and recommended there. It only helps with Paper's "
              + "per-player-mob-spawns on, which is Paper's default. Needs a restart.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasLeafGlobal()) return;
        ctx.leafGlobalYml().set(PATH, true);
        ctx.markLeafGlobalDirty();
    }
}

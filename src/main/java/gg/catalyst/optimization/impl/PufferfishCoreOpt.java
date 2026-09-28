// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class PufferfishCoreOpt implements Optimization {
    // All on by default in Pufferfish; this only catches a server that switched them off.
    private static final String[] KEYS = { "dab.enabled", "enable-async-mob-spawning", "enable-suffocation-optimization" };

    @Override public String id() { return "pufferfish.core"; }
    @Override public String configFile() { return "pufferfish.yml"; }
    @Override public String name() { return "Pufferfish Core Optimizations"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPufferfishConfig; }

    @Override
    public String description() {
        return "Dynamic activation of brain, off-thread mob spawning maths and the cheaper suffocation "
             + "check are the main reasons to run Pufferfish at all.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPufferfishYml()) return null;
        final YamlConfiguration cfg = ctx.pufferfishYml();
        final List<String> off = new ArrayList<>();

        for (final String key : KEYS)
            if (!cfg.getBoolean(key, true)) off.add(key);

        if (off.isEmpty()) return null;

        return CheckResult.of(String.join("=false ", off) + "=false", String.join("=true ", off) + "=true",
                "Pufferfish recommends leaving these on. DAB ticks far away mobs less often, so "
              + "very distant farms run a little slower. Async mob spawning needs a restart.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPufferfishYml()) return;
        final YamlConfiguration cfg = ctx.pufferfishYml();

        for (final String key : KEYS)
            if (!cfg.getBoolean(key, true)) cfg.set(key, true);
        ctx.markPufferfishYmlDirty();
    }
}

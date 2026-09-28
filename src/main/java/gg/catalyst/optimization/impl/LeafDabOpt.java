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

public final class LeafDabOpt implements Optimization {
    private static final String BASE = "performance.dab.";
    private static final String[] KEYS = { "enabled", "dont-enable-if-in-water" };

    @Override public String id() { return "leaf.dab"; }
    @Override public String configFile() { return "config/leaf-global.yml"; }
    @Override public String configKey() { return "performance.dab"; }
    @Override public String name() { return "Dynamic Activation of Brain"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasLeafConfig; }

    @Override
    public String description() {
        return "Ticks mob AI less often the further a mob is from players, on a gradient instead of "
             + "a hard cutoff. Off by default in Leaf.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasLeafGlobal()) return null;
        final YamlConfiguration cfg = ctx.leafGlobalYml();
        final List<String> off = new ArrayList<>();

        for (final String key : KEYS)
            if (!cfg.getBoolean(BASE + key, false)) off.add(key);

        if (off.isEmpty()) return null;

        return CheckResult.of(String.join("=false ", off) + "=false", String.join("=true ", off) + "=true",
                "Real trade-off: distant mobs think less often, so far away mob farms can slow down. "
              + "Villagers, axolotls, hoglins, piglins and goats are excluded by default, and "
              + "dont-enable-if-in-water stops distant land mobs drowning. Needs a restart.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasLeafGlobal()) return;
        final YamlConfiguration cfg = ctx.leafGlobalYml();

        for (final String key : KEYS)
            if (!cfg.getBoolean(BASE + key, false)) cfg.set(BASE + key, true);
        ctx.markLeafGlobalDirty();
    }
}

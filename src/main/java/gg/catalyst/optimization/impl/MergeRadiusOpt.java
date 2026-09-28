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

public final class MergeRadiusOpt implements Optimization {
    private static final String ITEM = "world-settings.default.merge-radius.item";
    private static final String EXP = "world-settings.default.merge-radius.exp";

    @Override public String id() { return "spigot.merge-radius"; }
    @Override public String configFile() { return "spigot.yml"; }
    @Override public String configKey() { return "world-settings.default.merge-radius"; }
    @Override public String name() { return "Item & XP Merge Radius"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasSpigotConfig; }

    @Override
    public String description() {
        return "merge-radius makes nearby dropped items and XP orbs combine into single entities. "
             + "Paper ships deliberately low defaults, so raising these is opt-in.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.spigotYml();
        final double item = cfg.getDouble(ITEM, 0.5);
        final double exp = cfg.getDouble(EXP, -1.0);

        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        if (item < 3.5) { now.add("item=" + item); rec.add("item=3.5"); }

        if (exp < 4.0) { now.add("exp=" + exp); rec.add("exp=4.0"); }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Big win on grinders and mass-mining, where hundreds of orbs and drops exist at once. "
              + "Trade-off: pushing these higher breaks some farms and lets items merge through "
              + "blocks, so these are the highest values generally considered safe.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.spigotYml();

        if (cfg.getDouble(ITEM, 0.5) < 3.5) cfg.set(ITEM, 3.5);

        if (cfg.getDouble(EXP, -1.0) < 4.0) cfg.set(EXP, 4.0);
        ctx.markSpigotYmlDirty();
    }
}

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

public final class PaperEntityOpt implements Optimization {
    private static final String COLLISIONS = "collisions.max-entity-collisions";
    private static final String CHEST_CAT = "entities.behavior.disable-chest-cat-detection";

    @Override public String id() { return "paper.entity-behavior"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return COLLISIONS + ", " + CHEST_CAT; }
    @Override public String name() { return "Paper Entity Behavior"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "Entities process collisions against up to 8 neighbours each tick, and "
             + "chest-cat detection scans the block above every chest a player opens.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;
        final YamlConfiguration cfg = ctx.paperWorldDefaultsYml();

        final int collisions = cfg.getInt(COLLISIONS, 8);
        final boolean chestCat = cfg.getBoolean(CHEST_CAT, false);

        final List<String> issues = new ArrayList<>();

        if (collisions > 2) issues.add("max-entity-collisions=" + collisions);

        if (!chestCat) issues.add("disable-chest-cat-detection=false");

        if (issues.isEmpty()) return null;

        return CheckResult.of(String.join(" ", issues),
                "max-entity-collisions=2 disable-chest-cat-detection=true",
                "The server stops processing collisions past the limit, so densely packed mobs "
              + "push each other apart less. Cats stop blocking chests, a vanilla quirk most "
              + "servers are happy to lose.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        final YamlConfiguration cfg = ctx.paperWorldDefaultsYml();
        cfg.set(COLLISIONS, 2);
        cfg.set(CHEST_CAT, true);
        ctx.markPaperWorldDirty();
    }
}

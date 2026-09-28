// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PurpurVillagerLobotomizeOpt implements Optimization {
    private static final String PATH = "world-settings.default.mobs.villager.lobotomize.enabled";

    @Override public String id() { return "purpur.villager-lobotomize"; }
    @Override public String configFile() { return "purpur.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Villager Lobotomize"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.EXPERIMENTAL; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPurpurConfig; }

    @Override
    public String description() {
        return "Strips the AI from villagers that cannot move, such as ones locked in trading cells. "
             + "They keep trading and restock now and then, but stop thinking.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return null;

        if (ctx.purpurYml().getBoolean(PATH, false)) return null;

        return CheckResult.of(false, true,
                "Only worth it if villagers are what is lagging the server: the extra pathfinding "
              + "check can cost more than it saves otherwise. Lobotomized villagers restock less often.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return;
        ctx.purpurYml().set(PATH, true);
        ctx.markPurpurYmlDirty();
    }
}

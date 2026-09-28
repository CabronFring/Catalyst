// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PurpurDolphinTreasureOpt implements Optimization {
    private static final String PATH = "world-settings.default.mobs.dolphin.disable-treasure-searching";

    @Override public String id() { return "purpur.dolphin-treasure-search"; }
    @Override public String configFile() { return "purpur.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Dolphin Treasure Search"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPurpurConfig; }

    @Override
    public String description() {
        return "Dolphins fed fish run a structure search for the nearest treasure, the same lookup "
             + "a treasure map does, and it can stall the main thread.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return null;

        if (ctx.purpurYml().getBoolean(PATH, false)) return null;

        return CheckResult.of(false, true,
                "Real trade-off: dolphins stop leading players to shipwrecks and buried treasure. "
              + "Nothing else about dolphins changes.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return;
        ctx.purpurYml().set(PATH, true);
        ctx.markPurpurYmlDirty();
    }
}

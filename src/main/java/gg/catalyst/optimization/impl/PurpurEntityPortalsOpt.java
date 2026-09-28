// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PurpurEntityPortalsOpt implements Optimization {
    private static final String PATH = "world-settings.default.gameplay-mechanics.entities-can-use-portals";

    @Override public String id() { return "purpur.entity-portals"; }
    @Override public String configFile() { return "purpur.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Entity Portal Use"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.EXPERIMENTAL; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPurpurConfig; }

    @Override
    public String description() {
        return "Stops every entity except players from using portals. An entity changing worlds "
             + "loads chunks on the main thread, which is how portal-based farms cause lag.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return null;

        if (!ctx.purpurYml().getBoolean(PATH, true)) return null;

        return CheckResult.of(true, false,
                "Real trade-off: mobs, items and minecarts can no longer go through nether or end "
              + "portals at all. Portal item transport and some gold and mob farms stop working.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return;
        ctx.purpurYml().set(PATH, false);
        ctx.markPurpurYmlDirty();
    }
}

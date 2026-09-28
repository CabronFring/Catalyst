// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class UserCacheOpt implements Optimization {
    private static final String PATH = "settings.save-user-cache-on-stop-only";

    @Override public String id() { return "spigot.user-cache"; }
    @Override public String configFile() { return "spigot.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "User Cache Save Timing"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasSpigotConfig; }

    @Override
    public String description() {
        return "By default the server writes usercache.json to disk every time a player joins. "
             + "Saving only on shutdown removes that disk write from the join path.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final boolean current = ctx.spigotYml().getBoolean(PATH, false);

        if (current) return null;

        return CheckResult.of(false, true,
                "The only downside is that a hard crash loses recent cache entries, "
              + "which the server simply rebuilds from Mojang on next join.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        ctx.spigotYml().set(PATH, true);
        ctx.markSpigotYmlDirty();
    }
}

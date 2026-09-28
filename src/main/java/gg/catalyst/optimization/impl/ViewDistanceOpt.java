// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;
import org.bukkit.Server;
import org.bukkit.World;

public final class ViewDistanceOpt implements Optimization {
    private static final int RECOMMENDED = 8;

    @Override public String id() { return "server.view-distance"; }
    @Override public String configFile() { return "server.properties"; }
    @Override public String configKey() { return "view-distance"; }
    @Override public String name() { return "View Distance"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return true; }
    @Override public boolean canApplyLive() { return true; }

    @Override
    public String description() {
        return "view-distance is how many chunks around each player are loaded and sent. "
             + "The default of 10 is generous - most servers run fine at 6 to 8.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final int current = intProp(ctx, "view-distance", 10);

        if (current <= RECOMMENDED) return null;

        return CheckResult.of(current, RECOMMENDED,
                "Each player at view-distance 10 loads roughly 1,257 chunks. At 8 that drops "
              + "to about 804 - a third fewer chunks to load, tick and send.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        ctx.serverProperties().setProperty("view-distance", String.valueOf(RECOMMENDED));
        ctx.markServerPropertiesDirty();
    }

    @Override
    public void applyLive(Server server) {
        for (final World w : server.getWorlds()) w.setViewDistance(RECOMMENDED);
    }

    private static int intProp(OptimizationContext ctx, String key, int fallback) {
        final String v = ctx.serverProperties().getProperty(key);

        if (v == null) return fallback;

        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}

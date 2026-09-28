// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;
import org.bukkit.Server;
import org.bukkit.World;

public final class SimulationDistanceOpt implements Optimization {
    private static final int RECOMMENDED = 6;

    @Override public String id() { return "server.simulation-distance"; }
    @Override public String configFile() { return "server.properties"; }
    @Override public String configKey() { return "simulation-distance"; }
    @Override public String name() { return "Simulation Distance"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return true; }
    @Override public boolean canApplyLive() { return true; }

    @Override
    public String description() {
        return "simulation-distance controls how far away entities and block ticks are processed. "
             + "The vanilla default of 10 is generous; 6 cuts the simulated area by ~64% "
             + "with little effect for players who stay near their builds.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        // simulation-distance only exists on 1.18+; on older servers there is nothing to tune.
        if (ctx.serverProperties().getProperty("simulation-distance") == null) return null;

        final int current = intProp(ctx, "simulation-distance", 10);

        if (current <= RECOMMENDED) return null;

        return CheckResult.of(current, RECOMMENDED,
                "Every extra chunk of simulation multiplies the entity tick load. "
              + "Trade-off: anything more than ~6 chunks (~96 blocks) from a player stops ticking, "
              + "so AFK machines and farms a player isn't standing near will pause.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        ctx.serverProperties().setProperty("simulation-distance", String.valueOf(RECOMMENDED));
        ctx.markServerPropertiesDirty();
    }

    @Override
    public void applyLive(Server server) {
        for (final World w : server.getWorlds()) w.setSimulationDistance(RECOMMENDED);
    }

    private static int intProp(OptimizationContext ctx, String key, int fallback) {
        final String v = ctx.serverProperties().getProperty(key);

        if (v == null) return fallback;

        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}

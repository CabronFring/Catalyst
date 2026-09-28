// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class TicksPerSpawnOpt implements Optimization {
    private static final int ANIMALS = 400;
    private static final int MONSTERS = 10;

    @Override public String id() { return "bukkit.ticks-per-spawn"; }
    @Override public String configFile() { return "bukkit.yml"; }
    @Override public String configKey() { return "ticks-per"; }
    @Override public String name() { return "Ticks Per Spawn"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return true; }
    @Override public boolean canApplyLive() { return true; }

    @Override
    public String description() {
        return "ticks-per controls how often the server runs the mob spawning algorithm. "
             + "Running it every single tick costs CPU without increasing mob density.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.bukkitYml();
        final int animals = cfg.getInt("ticks-per.animal-spawns", ANIMALS);
        final int monsters = cfg.getInt("ticks-per.monster-spawns", 1);

        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        if (animals < ANIMALS) { now.add("animal-spawns=" + animals); rec.add("animal-spawns=" + ANIMALS); }

        if (monsters < MONSTERS) { now.add("monster-spawns=" + monsters); rec.add("monster-spawns=" + MONSTERS); }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Spawn attempts are capped by the spawn limits either way, so running the "
              + "algorithm less often reaches the same mob population with far less work.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.bukkitYml();

        if (cfg.getInt("ticks-per.animal-spawns", ANIMALS) < ANIMALS)
            cfg.set("ticks-per.animal-spawns", ANIMALS);

        if (cfg.getInt("ticks-per.monster-spawns", 1) < MONSTERS)
            cfg.set("ticks-per.monster-spawns", MONSTERS);
        ctx.markBukkitYmlDirty();
    }

    /** Per-type setters kept deliberately for 1.16 compatibility; see SpawnLimitsOpt. */
    @SuppressWarnings("deprecation")
    @Override
    public void applyLive(Server server) {
        for (final World w : server.getWorlds()) {
            if (w.getTicksPerAnimalSpawns() < ANIMALS) w.setTicksPerAnimalSpawns(ANIMALS);

            if (w.getTicksPerMonsterSpawns() < MONSTERS) w.setTicksPerMonsterSpawns(MONSTERS);
        }
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpawnLimitsOpt implements Optimization {
    private static final Map<String, Integer> TARGETS = new LinkedHashMap<>();
    static {
        TARGETS.put("monsters", 50);
        TARGETS.put("animals", 8);
        TARGETS.put("water-animals", 3);
        TARGETS.put("water-ambient", 10);
        TARGETS.put("ambient", 4);
    }

    private static final Map<String, Integer> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("monsters", 70);
        DEFAULTS.put("animals", 10);
        DEFAULTS.put("water-animals", 5);
        DEFAULTS.put("water-ambient", 20);
        DEFAULTS.put("ambient", 15);
    }

    @Override public String id() { return "bukkit.spawn-limits"; }
    @Override public String configFile() { return "bukkit.yml"; }
    @Override public String configKey() { return "spawn-limits"; }
    @Override public String name() { return "Spawn Limits"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.SAFE; }
    @Override public boolean appliesTo(ServerPlatform p) { return true; }
    @Override public boolean canApplyLive() { return true; }

    @Override
    public String description() {
        return "spawn-limits caps how many mobs of each category can exist per world. "
             + "Vanilla defaults are generous - mob density stays natural well below them.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.bukkitYml();
        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        for (final var entry : TARGETS.entrySet()) {
            final String key = entry.getKey();
            final int target = entry.getValue();
            final int current = cfg.getInt("spawn-limits." + key, DEFAULTS.get(key));

            if (current > target) {
                now.add(key + "=" + current);
                rec.add(key + "=" + target);
            }
        }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Mob tick cost scales directly with how many are alive. These caps keep the "
              + "world feeling populated while removing the long tail of mobs nobody sees.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.bukkitYml();

        for (final var entry : TARGETS.entrySet()) {
            final String key = entry.getKey();
            final int target = entry.getValue();

            if (cfg.getInt("spawn-limits." + key, DEFAULTS.get(key)) > target)
                cfg.set("spawn-limits." + key, target);
        }
        ctx.markBukkitYmlDirty();
    }

    /**
     * Uses the per-type setters rather than setSpawnLimit(SpawnCategory, int) on purpose.
     * SpawnCategory only exists on 1.18 and newer, and Catalyst still supports 1.16, where
     * referencing it would fail to link. These have been deprecated-but-working for years.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void applyLive(Server server) {
        final int monsters = TARGETS.get("monsters");
        final int animals = TARGETS.get("animals");
        final int water = TARGETS.get("water-animals");
        final int wAmbient = TARGETS.get("water-ambient");
        final int ambient = TARGETS.get("ambient");

        for (final World w : server.getWorlds()) {
            if (w.getMonsterSpawnLimit() > monsters) w.setMonsterSpawnLimit(monsters);

            if (w.getAnimalSpawnLimit() > animals) w.setAnimalSpawnLimit(animals);

            if (w.getWaterAnimalSpawnLimit() > water) w.setWaterAnimalSpawnLimit(water);

            if (w.getWaterAmbientSpawnLimit() > wAmbient) w.setWaterAmbientSpawnLimit(wAmbient);

            if (w.getAmbientSpawnLimit() > ambient) w.setAmbientSpawnLimit(ambient);
        }
    }
}

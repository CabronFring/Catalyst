// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityActivationRangeOpt implements Optimization {
    private static final String BASE = "world-settings.default.entity-activation-range.";

    private static final Map<String, Integer> TARGETS = new LinkedHashMap<>();
    static {
        TARGETS.put("animals", 16);
        TARGETS.put("monsters", 24);
        TARGETS.put("villagers", 16);
        TARGETS.put("misc", 8);
    }

    private static final Map<String, Integer> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("animals", 32);
        DEFAULTS.put("monsters", 32);
        DEFAULTS.put("villagers", 32);
        DEFAULTS.put("misc", 16);
    }

    @Override public String id() { return "spigot.entity-activation-range"; }
    @Override public String configFile() { return "spigot.yml"; }
    @Override public String configKey() { return "world-settings.default.entity-activation-range"; }
    @Override public String name() { return "Entity Activation Range"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasSpigotConfig; }

    @Override
    public String description() {
        return "How close a player must be before an entity runs its full AI. Entities beyond "
             + "the range still exist and are visible, they just barely tick.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.spigotYml();
        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        for (final var entry : TARGETS.entrySet()) {
            final String key = entry.getKey();
            final int target = entry.getValue();
            final int current = cfg.getInt(BASE + key, DEFAULTS.get(key));

            if (current > target) {
                now.add(key + "=" + current);
                rec.add(key + "=" + target);
            }
        }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Mob AI is the largest tick cost on most survival servers. Monsters stay at 24 so "
              + "combat feels normal. Trade-off: mobs further out react more slowly, which can "
              + "affect long-range farm designs. Raiders are left alone so raids work correctly.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.spigotYml();

        for (final var entry : TARGETS.entrySet()) {
            final String key = entry.getKey();
            final int target = entry.getValue();

            if (cfg.getInt(BASE + key, DEFAULTS.get(key)) > target)
                cfg.set(BASE + key, target);
        }
        ctx.markSpigotYmlDirty();
    }
}

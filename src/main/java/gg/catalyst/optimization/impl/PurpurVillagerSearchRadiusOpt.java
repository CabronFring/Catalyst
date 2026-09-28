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

public final class PurpurVillagerSearchRadiusOpt implements Optimization {
    private static final String BASE = "world-settings.default.mobs.villager.search-radius.";
    private static final String[] KEYS = { "acquire-poi", "nearest-bed-sensor" };
    private static final int DEFAULT = 48;
    private static final int TARGET = 16;

    @Override public String id() { return "purpur.villager-search-radius"; }
    @Override public String configFile() { return "purpur.yml"; }
    @Override public String configKey() { return "world-settings.default.mobs.villager.search-radius"; }
    @Override public String name() { return "Villager Search Radius"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPurpurConfig; }

    @Override
    public String description() {
        return "How far villagers scan for job sites and beds. Every villager without one searches "
             + "a 48 block radius over and over, which adds up fast in trading halls.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return null;
        final YamlConfiguration cfg = ctx.purpurYml();
        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        for (final String key : KEYS) {
            final int current = cfg.getInt(BASE + key, DEFAULT);

            if (current > TARGET) {
                now.add(key + "=" + current);
                rec.add(key + "=" + TARGET);
            }
        }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Real trade-off: villagers no longer notice job site blocks or beds more than 16 "
              + "blocks away. Normal villages and trading halls are well inside that.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPurpurYml()) return;
        final YamlConfiguration cfg = ctx.purpurYml();

        for (final String key : KEYS)
            if (cfg.getInt(BASE + key, DEFAULT) > TARGET) cfg.set(BASE + key, TARGET);
        ctx.markPurpurYmlDirty();
    }
}

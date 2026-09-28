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
import java.util.Map;

public final class CanvasVillagerPoiOpt implements Optimization {
    private static final String BASE = "entities.villagers.";

    // Each reduce option drops that search from 48 blocks to 16.
    private static final Map<String, Boolean> TARGETS = Map.of(
            "reduce-job-site-poi-search-range", true,
            "reduce-home-poi-search-range", true,
            "reduce-meeting-point-poi-search-range", true,
            "villager-acquire-poi-tasks-load-chunks", false);

    @Override public String id() { return "canvas.villager-poi"; }
    @Override public String configFile() { return "config/canvas-worlds.yml"; }
    @Override public String configKey() { return "entities.villagers"; }
    @Override public String name() { return "Villager POI Search"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasCanvasConfig; }

    @Override
    public String description() {
        return "Villagers without a job site, bed or bell search 48 blocks around them for one, and "
             + "can load unloaded chunks to do it. Canvas can shrink that to 16 and keep chunks unloaded.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasCanvasWorlds()) return null;
        final YamlConfiguration cfg = ctx.canvasWorldsYml();
        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        for (final var entry : TARGETS.entrySet()) {
            final boolean target = entry.getValue();
            final boolean current = cfg.getBoolean(BASE + entry.getKey(), !target);

            if (current != target) {
                now.add(entry.getKey() + "=" + current);
                rec.add(entry.getKey() + "=" + target);
            }
        }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Real trade-off: villagers no longer notice job site blocks, beds or bells more than 16 "
              + "blocks away, or any in unloaded chunks. A world's canvas-patch.yml can still override this.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasCanvasWorlds()) return;
        final YamlConfiguration cfg = ctx.canvasWorldsYml();

        for (final var entry : TARGETS.entrySet())
            cfg.set(BASE + entry.getKey(), entry.getValue());
        ctx.markCanvasWorldsDirty();
    }
}

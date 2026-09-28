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

public final class CanvasChunkSystemOpt implements Optimization {
    private static final String FLUID = "chunk-system.fluid-post-processing-algorithm";
    private static final String TREASURE = "chunk-system.optimize-treasure-map-locating";

    @Override public String id() { return "canvas.chunk-system"; }
    @Override public String configFile() { return "config/canvas-server.yml"; }
    @Override public String configKey() { return "chunk-system"; }
    @Override public String name() { return "Chunk Generation Shortcuts"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasCanvasConfig; }

    @Override
    public String description() {
        return "World generation queues a lot of fluid updates that do nothing, which stutters the server "
             + "while new chunks generate. Treasure maps also run an expensive structure search when made.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasCanvasServer()) return null;
        final YamlConfiguration cfg = ctx.canvasServerYml();
        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();
        final String fluid = cfg.getString(FLUID, "VANILLA");

        if ("VANILLA".equalsIgnoreCase(fluid)) {
            now.add("fluid-post-processing-algorithm=" + fluid);
            rec.add("fluid-post-processing-algorithm=FILTERED");
        }

        if (!cfg.getBoolean(TREASURE, false)) {
            now.add("optimize-treasure-map-locating=false");
            rec.add("optimize-treasure-map-locating=true");
        }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "FILTERED is C2ME's filter for the useless fluid updates, not vanilla's code, so new chunks "
              + "can differ in rare water and lava edge cases. DISABLED is left alone: it skips them all.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasCanvasServer()) return;
        final YamlConfiguration cfg = ctx.canvasServerYml();

        if ("VANILLA".equalsIgnoreCase(cfg.getString(FLUID, "VANILLA"))) cfg.set(FLUID, "FILTERED");
        cfg.set(TREASURE, true);
        ctx.markCanvasServerDirty();
    }
}

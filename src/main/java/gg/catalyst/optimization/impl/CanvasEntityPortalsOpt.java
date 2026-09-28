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

public final class CanvasEntityPortalsOpt implements Optimization {
    private static final String PREFIX = "blacklist-non-player-entities-from-entering-";
    private static final String[] PORTALS = { "nether", "end", "gateway" };

    @Override public String id() { return "canvas.entity-portals"; }
    @Override public String configFile() { return "config/canvas-server.yml"; }
    @Override public String name() { return "Entity Portal Use"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.EXPERIMENTAL; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasCanvasConfig; }

    @Override
    public String description() {
        return "Stops every entity except players from using portals. On Folia an entity changing worlds "
             + "also has to move between regions, which is how portal-based farms cause lag.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasCanvasServer()) return null;
        final YamlConfiguration cfg = ctx.canvasServerYml();
        final List<String> open = new ArrayList<>();

        for (final String portal : PORTALS)
            if (!cfg.getBoolean(PREFIX + portal + "-portals", false)) open.add(portal);

        if (open.isEmpty()) return null;

        return CheckResult.of("allowed: " + String.join(", ", open), "players only",
                "Real trade-off: mobs, items and minecarts can no longer go through nether, end or "
              + "gateway portals at all. Portal item transport and some gold and mob farms stop working.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasCanvasServer()) return;
        final YamlConfiguration cfg = ctx.canvasServerYml();

        for (final String portal : PORTALS) cfg.set(PREFIX + portal + "-portals", true);
        ctx.markCanvasServerDirty();
    }
}

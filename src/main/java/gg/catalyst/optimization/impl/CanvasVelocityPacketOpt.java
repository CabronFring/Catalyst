// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class CanvasVelocityPacketOpt implements Optimization {
    private static final String PATH = "networking.filter-velocity-packet";

    @Override public String id() { return "canvas.velocity-packets"; }
    @Override public String category() { return "network"; }
    @Override public String configFile() { return "config/canvas-server.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Entity Velocity Packet Filter"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasCanvasConfig; }

    @Override
    public String description() {
        return "Entity velocity packets can be most of a busy server's outgoing traffic. Canvas can drop "
             + "the ones that change nothing a player would see.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasCanvasServer()) return null;

        if (ctx.canvasServerYml().getBoolean(PATH, false)) return null;

        return CheckResult.of(false, true,
                "Canvas keeps vanilla's visuals. Test any anticheat or plugin that reads velocity packets, "
              + "since it will see fewer of them.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasCanvasServer()) return;
        ctx.canvasServerYml().set(PATH, true);
        ctx.markCanvasServerDirty();
    }
}

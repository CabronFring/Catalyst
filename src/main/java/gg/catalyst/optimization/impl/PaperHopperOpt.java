// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

public final class PaperHopperOpt implements Optimization {
    private static final String PATH = "hopper.ignore-occluding-blocks";

    @Override public String id() { return "paper.hopper-occlusion"; }
    @Override public String configFile() { return "config/paper-world-defaults.yml"; }
    @Override public String configKey() { return PATH; }
    @Override public String name() { return "Hopper Occlusion Check"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasPaperConfig; }

    @Override
    public String description() {
        return "Makes hoppers skip looking for containers hidden inside solid blocks, "
             + "which removes a lookup from every transfer attempt on storage-heavy servers.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return null;

        if (ctx.paperWorldDefaultsYml().getBoolean(PATH, false)) return null;

        return CheckResult.of(false, true,
                "Real trade-off: hoppers stop seeing containers buried in occluding blocks, "
              + "so hopper minecarts under sand or gravel stop collecting. Several common "
              + "farm designs rely on exactly that, so check your builds before enabling.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        if (!ctx.hasPaperWorldDefaults()) return;
        ctx.paperWorldDefaultsYml().set(PATH, true);
        ctx.markPaperWorldDirty();
    }
}

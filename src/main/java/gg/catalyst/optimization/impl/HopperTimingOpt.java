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

public final class HopperTimingOpt implements Optimization {
    private static final String BASE = "world-settings.default.ticks-per.";

    // hopper-transfer 8 is vanilla, so it is only raised when someone set it lower.
    private static final int TRANSFER = 8;
    private static final int CHECK = 8;
    private static final int CHECK_DEFAULT = 1;

    @Override public String id() { return "spigot.hopper-timing"; }
    @Override public String configFile() { return "spigot.yml"; }
    @Override public String configKey() { return "world-settings.default.ticks-per"; }
    @Override public String name() { return "Hopper Transfer/Check Timing"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.EXPERIMENTAL; }
    @Override public boolean appliesTo(ServerPlatform p) { return p.hasSpigotConfig; }

    @Override
    public String description() {
        return "How many ticks a hopper waits between looking for items to pull. By default an idle "
             + "hopper checks every tick, so servers with thousands of hoppers pay for it constantly.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.spigotYml();
        final int transfer = cfg.getInt(BASE + "hopper-transfer", TRANSFER);
        final int check = cfg.getInt(BASE + "hopper-check", CHECK_DEFAULT);
        final List<String> now = new ArrayList<>();
        final List<String> rec = new ArrayList<>();

        if (transfer < TRANSFER) {
            now.add("hopper-transfer=" + transfer);
            rec.add("hopper-transfer=" + TRANSFER);
        }

        if (check < CHECK) {
            now.add("hopper-check=" + check);
            rec.add("hopper-check=" + CHECK);
        }

        if (now.isEmpty()) return null;

        return CheckResult.of(String.join(" ", now), String.join(" ", rec),
                "Real trade-off: hoppers notice new items up to 8 ticks later. Spigot warns this "
              + "desyncs most hopper contraptions, so hopper clocks and item sorters can break. "
              + "Item throughput of a running chain stays the same. Only worth it on hopper-heavy servers.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        final YamlConfiguration cfg = ctx.spigotYml();

        if (cfg.getInt(BASE + "hopper-transfer", TRANSFER) < TRANSFER)
            cfg.set(BASE + "hopper-transfer", TRANSFER);

        if (cfg.getInt(BASE + "hopper-check", CHECK_DEFAULT) < CHECK)
            cfg.set(BASE + "hopper-check", CHECK);
        ctx.markSpigotYmlDirty();
    }
}

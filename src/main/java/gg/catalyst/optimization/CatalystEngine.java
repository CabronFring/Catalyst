// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

import gg.catalyst.optimization.impl.*;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.platform.ServerPlatform;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class CatalystEngine {
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final BackupManager backups;
    private final List<Optimization> optimizations;

    public CatalystEngine(Plugin plugin) {
        this.plugin = plugin;
        this.platform = PlatformDetector.detect();
        this.backups = new BackupManager(plugin);
        this.optimizations = List.of(
                new SimulationDistanceOpt(),
                new NetworkCompressionOpt(),
                new ViewDistanceOpt(),
                new SpawnLimitsOpt(),
                new TicksPerSpawnOpt(),
                new MergeRadiusOpt(),
                new EntityActivationRangeOpt(),
                new TickInactiveVillagersOpt(),
                new UserCacheOpt(),
                new PaperTickRatesOpt(),
                new PaperSpawnerTickRateOpt(),
                new PaperEntityOpt(),
                new PaperMiscOpt(),
                new PaperHopperOpt(),
                new PaperPathfindingOpt(),
                new PaperRedstoneOpt()
        );
    }

    public ServerPlatform platform() { return this.platform; }
    public BackupManager backups() { return this.backups; }

    /** Levels that at least one optimization belongs to, lowest first: the ones worth offering. */
    public List<OptimizationLevel> levelsInUse() {
        return Arrays.stream(OptimizationLevel.values())
                .filter(l -> this.optimizations.stream().anyMatch(o -> o.level() == l)).toList();
    }

    public ScanResult scan() {
        final OptimizationContext ctx = this.newContext();
        final Set<String> ignored = Set.copyOf(this.plugin.getConfig().getStringList("ignored-optimizations"));

        final ScanResult result = new ScanResult(this.platform);

        for (final Optimization opt : this.optimizations) {
            if (!opt.appliesTo(this.platform)) continue;

            if (ignored.contains(opt.id())) continue;

            try {
                final CheckResult check = opt.check(ctx);

                if (check == null) result.addPassing(opt);
                else result.addFinding(opt, check);
            } catch (Exception e) {
                this.plugin.getLogger().warning("Check " + opt.id() + " failed: " + e.getMessage());
            }
        }

        return result;
    }

    /** Applies every pending optimization up to and including this level. */
    public ApplyResult apply(OptimizationLevel maxLevel) {
        return this.apply(opt -> includes(maxLevel, opt.level()));
    }

    /** Applies only the named optimizations, whatever level they belong to (a single recommendation's fix). */
    public ApplyResult applyOnly(Set<String> ids) {
        return this.apply(opt -> ids.contains(opt.id()));
    }

    private ApplyResult apply(java.util.function.Predicate<Optimization> selected) {
        final OptimizationContext ctx = this.newContext();
        final Set<String> ignored = Set.copyOf(this.plugin.getConfig().getStringList("ignored-optimizations"));
        final ApplyResult result = new ApplyResult();

        try {
            result.setBackupDir(this.backups.backup(ctx));
        } catch (Exception e) {
            result.addError("Backup failed, nothing was changed: " + e.getMessage());
            return result;
        }

        for (final Optimization opt : this.optimizations) {
            if (!opt.appliesTo(this.platform)) continue;

            if (ignored.contains(opt.id())) continue;

            if (!selected.test(opt)) continue;

            try {
                if (opt.check(ctx) == null) continue;
                opt.apply(ctx);
                result.addApplied(opt);
            } catch (Exception e) {
                result.addError(opt.id() + ": " + e.getMessage());
            }
        }

        try {
            ctx.saveAll();
        } catch (Exception e) {
            result.addError("Could not write config files: " + e.getMessage());
        }

        return result;
    }

    /** Every optimization id that applies to this platform, for validating a targeted apply. */
    public Set<String> optimizationIds() {
        final Set<String> ids = new java.util.LinkedHashSet<>();

        for (final Optimization opt : this.optimizations)
            if (opt.appliesTo(this.platform)) ids.add(opt.id());

        return ids;
    }

    private OptimizationContext newContext() {
        final File root = PlatformDetector.serverRoot();

        return new OptimizationContext(this.platform, root, this.plugin.getLogger());
    }

    private static boolean includes(OptimizationLevel max, OptimizationLevel level) {
        return level.ordinal() <= max.ordinal();
    }

    /** Reads a value straight from server.properties, for read-only reporting. */
    public String serverProperty(String key, String fallback) {
        final String value = this.newContext().serverProperties().getProperty(key);

        return value == null ? fallback : value.trim();
    }

    /** True when BungeeCord or Velocity forwarding is switched on. */
    public boolean behindProxy() {
        final OptimizationContext ctx = this.newContext();
        final boolean bungee = ctx.spigotYml().getBoolean("settings.bungeecord", false);
        final boolean velocity = ctx.hasPaperGlobalModern()
                && ctx.paperGlobalYml().getBoolean("proxies.velocity.enabled", false);

        return bungee || velocity;
    }
}

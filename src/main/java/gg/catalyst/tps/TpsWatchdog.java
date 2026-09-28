// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import gg.catalyst.Catalyst;
import gg.catalyst.event.CatalystLowTpsEvent;
import gg.catalyst.platform.PlatformDetector;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Watches tick rate and reacts when it falls. Deliberately quiet: one line per incident,
 * rate limited, because an admin who gets spammed stops reading the warnings entirely.
 */
public final class TpsWatchdog {
    private final Catalyst plugin;
    private final TpsSource source;

    private boolean enabled;
    private long intervalSeconds;
    private double warnBelow;
    private long cooldownSeconds;
    private List<String> commands;

    private long lastReportAt = 0L;
    private boolean started = false;
    private double lowestSeen = Double.NaN;

    public TpsWatchdog(Catalyst plugin, TpsSource source) {
        this.plugin = plugin;
        this.source = source;
    }

    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("tps-watchdog.enabled", true);
        this.intervalSeconds = Math.max(5, cfg.getLong("tps-watchdog.check-interval-seconds", 30));
        this.warnBelow = cfg.getDouble("tps-watchdog.warn-below", 15.0);
        this.cooldownSeconds = Math.max(0, cfg.getLong("tps-watchdog.cooldown-seconds", 300));
        this.commands = cfg.getStringList("tps-watchdog.commands");
    }

    public boolean isEnabled() { return this.enabled && this.source.isAvailable(); }
    public TpsSource source() { return this.source; }
    public double lowestSeen() { return this.lowestSeen; }

    public String status() {
        if (!this.source.isAvailable()) return "TPS " + this.source.describe();

        if (!this.enabled) return "disabled";

        return String.format("watching, warns below %.1f, checked every %ds", this.warnBelow, this.intervalSeconds);
    }

    /** Starts the self-rescheduling check loop. Safe to call once, at enable. */
    public void start() {
        if (this.started) return;
        this.started = true;
        this.scheduleNext();
    }

    private void scheduleNext() {
        this.plugin.runDelayed(this::check, this.intervalSeconds);
    }

    private void check() {
        try {
            if (!this.enabled || !this.source.isAvailable()) return;

            final double tps = this.source.currentTps();

            if (Double.isNaN(this.lowestSeen) || tps < this.lowestSeen) this.lowestSeen = tps;

            if (tps < this.warnBelow) this.report(tps);
        } finally {
            // Reschedule even after a failure, or one hiccup would silently end the loop.
            this.scheduleNext();
        }
    }

    /** On Folia the reading is one region's, so say which; elsewhere it is the whole server. */
    private static String where() {
        if (!PlatformDetector.isFolia()) return "";
        final FoliaTicks.Region r = FoliaTicks.slowest(FoliaTicks.regions());

        if (r == null) return "";

        return String.format(" in the region around %d, %d in %s", r.blockX(), r.blockZ(), r.world().getName());
    }

    private void report(double tps) {
        final long now = System.currentTimeMillis();

        if (now - this.lastReportAt < this.cooldownSeconds * 1000L) return;
        this.lastReportAt = now;

        final String where = where();
        this.plugin.getLogger().warning(String.format(
                "TPS is %.1f (below %.1f)%s. Run /catalyst config check for configuration causes, "
              + "or /catalyst modules to see what runtime limiting has done.", tps, this.warnBelow, where));

        // The event and the commands both touch server state, and the check loop runs off the
        // server thread, so hop onto it for both.
        final String region = where.replaceFirst("^ in ", "");
        this.plugin.runGlobal(() -> {
            try {
                Bukkit.getPluginManager().callEvent(new CatalystLowTpsEvent(tps, this.warnBelow, region));
            } catch (Throwable t) {
                this.plugin.getLogger().warning("TPS watchdog event failed: " + t);
            }

            for (final String command : this.commands) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (Throwable t) {
                    this.plugin.getLogger().warning("TPS watchdog command failed: " + command + " (" + t + ")");
                }
            }
        });
    }
}

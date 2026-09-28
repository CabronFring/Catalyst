// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import gg.catalyst.chat.ChatLinks;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.report.Rating;
import gg.catalyst.tps.FoliaTicks;
import gg.catalyst.tps.RegionReport;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Guards every touch of spark behind a class check: SparkBridge uses spark's types, so just
 * loading it without spark would throw.
 */
public final class SparkSupport {
    private SparkSupport() {}

    public static boolean isAvailable() {
        try {
            Class.forName("me.lucko.spark.api.SparkProvider");
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }

        return SparkBridge.isPresent();
    }

    /**
     * Sends the whole diagnosis as coloured chat lines.
     * Returns the snapshot used, so callers can decide on follow-up suggestions.
     */
    public static SparkBridge.Snapshot send(CommandSender to, ChatLinks links) {
        SparkBridge.Snapshot s = SparkBridge.snapshot();

        to.sendMessage(ChatColor.AQUA + "Diagnosis " + ChatColor.GRAY + "(from spark statistics)");

        // On Folia spark averages every region together, so one lagging region disappears
        // among idle ones. Folia's own per-region figures are used instead, and the diagnosis
        // below is made from the slowest region, which is the one players notice.
        final List<FoliaTicks.Region> regions = PlatformDetector.isFolia() ? FoliaTicks.regions() : null;

        if (regions != null) {
            RegionReport.send(to, regions, links, true);
            s = forRegion(s, FoliaTicks.slowest(regions));
        } else {
            to.sendMessage(String.format(ChatColor.GRAY + "  TPS  %s%.1f " + ChatColor.DARK_GRAY + "now  %s%.1f " + ChatColor.DARK_GRAY + "1m  %s%.1f " + ChatColor.DARK_GRAY + "15m",
                    Rating.forTps(s.tps5s()).color, s.tps5s(),
                    Rating.forTps(s.tps1m()).color, s.tps1m(),
                    Rating.forTps(s.tps15m()).color, s.tps15m()));

            if (s.hasMspt())
                to.sendMessage(String.format(ChatColor.GRAY + "  Tick %s%.1fms " + ChatColor.DARK_GRAY + "avg  " + ChatColor.GRAY + "%.1fms " + ChatColor.DARK_GRAY + "median  " + ChatColor.GRAY + "%.1fms " + ChatColor.DARK_GRAY + "worst 5%%  " + ChatColor.GRAY + "%.1fms " + ChatColor.DARK_GRAY + "peak",
                        Rating.forTps(20_000.0 / Math.max(1, s.msptMean() * 20)).color,
                        s.msptMean(), s.msptMedian(), s.mspt95th(), s.msptMax()));
            else
                to.sendMessage(ChatColor.GRAY + "  Tick " + ChatColor.DARK_GRAY + "n/a - spark cannot time ticks on this platform");
        }

        to.sendMessage(String.format(ChatColor.GRAY + "  CPU  " + ChatColor.WHITE + "%.0f%% " + ChatColor.DARK_GRAY + "server  " + ChatColor.WHITE + "%.0f%% " + ChatColor.DARK_GRAY + "whole machine",
                s.cpuProcess() * 100, s.cpuSystem() * 100));

        if (s.gcCollections() > 0)
            to.sendMessage(String.format(ChatColor.GRAY + "  GC   " + ChatColor.WHITE + "%s " + ChatColor.DARK_GRAY + "- %.0fms every %.1fs",
                    s.gcName(), s.gcAvgTimeMs(), s.gcAvgFrequencyMs() / 1000.0));

        to.sendMessage("");

        for (final Diagnosis.Culprit c : Diagnosis.analyse(s)) {
            final String colour = c.confidence() >= 75 ? "§c"
                          : c.confidence() >= 50 ? "§e"
                          : "§a";
            to.sendMessage(colour + "  " + c.title());
            to.sendMessage(ChatColor.DARK_GRAY + "     " + c.evidence());
            to.sendMessage(ChatColor.GRAY + "     " + c.action());
        }

        to.sendMessage("");

        // Stats say how the server and host are behaving; only a profile can say which code
        // is responsible, so point there for that.
        to.sendMessage(ChatColor.DARK_GRAY + "  Read from timing, CPU and GC statistics, so this says how the server");
        to.sendMessage(ChatColor.DARK_GRAY + "  is behaving, not which code is to blame. For that, run");
        to.sendMessage(ChatColor.DARK_GRAY + "  /catalyst perf profile, which names the methods and plugins.");

        return s;
    }

    /** spark's snapshot with its tick figures swapped for one region's. No region: nothing ticks, nothing lags. */
    private static SparkBridge.Snapshot forRegion(SparkBridge.Snapshot s, FoliaTicks.Region r) {
        if (r == null)
            return new SparkBridge.Snapshot(20, 20, 20, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    s.cpuProcess(), s.cpuSystem(), s.gcCollections(), s.gcAvgTimeMs(), s.gcAvgFrequencyMs(), s.gcName());

        return new SparkBridge.Snapshot(r.tps5s(), r.tps1m(), r.tps15m(),
                r.msptAvg(), r.msptMedian(), r.mspt95th(), r.msptMax(),
                s.cpuProcess(), s.cpuSystem(), s.gcCollections(), s.gcAvgTimeMs(), s.gcAvgFrequencyMs(), s.gcName());
    }
}

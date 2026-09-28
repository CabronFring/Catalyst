// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;

import java.util.Map;

/**
 * Reads spark's live statistics: tick times, CPU and GC. spark's public API exposes these
 * summaries but not the sampler, so there is no method-level detail here - only timing shape.
 * Loaded only when spark is present, so it never links on servers without it.
 */
public final class SparkBridge {
    public record Snapshot(
            double tps5s, double tps1m, double tps15m,
            double msptMean, double msptMedian, double mspt95th, double msptMax,
            double cpuProcess, double cpuSystem,
            long gcCollections, double gcAvgTimeMs, long gcAvgFrequencyMs, String gcName
    ) {
        /** False where spark cannot time ticks (Spigot, Folia): the mspt fields are then NaN. */
        public boolean hasMspt() { return !Double.isNaN(msptMean); }
    }

    private SparkBridge() {}

    public static boolean isPresent() {
        try {
            return SparkProvider.get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Snapshot snapshot() {
        final Spark spark = SparkProvider.get();

        final double tps5s = spark.tps().poll(StatisticWindow.TicksPerSecond.SECONDS_5);
        final double tps1m = spark.tps().poll(StatisticWindow.TicksPerSecond.MINUTES_1);
        final double tps15m = spark.tps().poll(StatisticWindow.TicksPerSecond.MINUTES_15);

        // Null on Spigot, where spark has no way to time a tick; all zeros on Folia, where there
        // is no single tick to time. Either way there are no tick times to report.
        final var msptStat = spark.mspt();
        DoubleAverageInfo mspt = msptStat == null ? null : msptStat.poll(StatisticWindow.MillisPerTick.MINUTES_1);

        if (mspt != null && mspt.max() <= 0) mspt = null;

        final double cpuProcess = spark.cpuProcess().poll(StatisticWindow.CpuUsage.MINUTES_1);
        final double cpuSystem = spark.cpuSystem().poll(StatisticWindow.CpuUsage.MINUTES_1);

        // Report the collector doing the most work, which is the one worth naming.
        long collections = 0;
        double avgTime = 0;
        long avgFrequency = 0;
        String name = "none";

        final Map<String, GarbageCollector> collectors = spark.gc();

        if (collectors != null) {
            for (final GarbageCollector gc : collectors.values()) {
                if (gc.totalCollections() <= collections) continue;
                collections = gc.totalCollections();
                avgTime = gc.avgTime();
                avgFrequency = gc.avgFrequency();
                name = gc.name();
            }
        }

        return new Snapshot(tps5s, tps1m, tps15m,
                mspt == null ? Double.NaN : mspt.mean(), mspt == null ? Double.NaN : mspt.median(),
                mspt == null ? Double.NaN : mspt.percentile95th(), mspt == null ? Double.NaN : mspt.max(),
                cpuProcess, cpuSystem,
                collections, avgTime, avgFrequency, name);
    }
}

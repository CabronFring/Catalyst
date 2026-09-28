// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.event;

import gg.catalyst.bench.Recommendations;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * A perf bench run finished. Carries the same figures and recommendations that went to chat, on
 * the main thread, for anything that wants to log or forward them. The lists are snapshots.
 */
public final class CatalystBenchmarkCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final int durationSeconds;
    private final double baselineMsPerTick;
    private final List<String> reportLines;
    private final List<Recommendations.Item> recommendations;

    public CatalystBenchmarkCompleteEvent(int durationSeconds, double baselineMsPerTick,
                                          List<String> reportLines, List<Recommendations.Item> recommendations) {
        this.durationSeconds = durationSeconds;
        this.baselineMsPerTick = baselineMsPerTick;
        this.reportLines = List.copyOf(reportLines);
        this.recommendations = List.copyOf(recommendations);
    }

    public int getDurationSeconds() { return this.durationSeconds; }

    /** The warm baseline each stage was measured against, ms per tick. */
    public double getBaselineMsPerTick() { return this.baselineMsPerTick; }

    /** The report as it was written, one entry per line. */
    public List<String> getReportLines() { return this.reportLines; }

    /** Biggest estimated cost first; empty when nothing stood out. */
    public List<Recommendations.Item> getRecommendations() { return this.recommendations; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}

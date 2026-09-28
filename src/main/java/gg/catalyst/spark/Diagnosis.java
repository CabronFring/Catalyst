// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns spark's timing statistics into plain findings. Only the inferences that hold from
 * timing shape alone; each finding carries the numbers behind it.
 */
public final class Diagnosis {
    private static final double TICK_BUDGET_MS = 50.0; // 20 TPS

    public record Culprit(int confidence, String title, String evidence, String action) {}

    private Diagnosis() {}

    public static List<Culprit> analyse(SparkBridge.Snapshot s) {
        final List<Culprit> out = new ArrayList<>();

        // Without tick times (spark on Spigot and CraftBukkit), TPS alone decides. Every tick-time
        // check below compares against NaN, which is always false, so they simply do not fire.
        final boolean healthy = (!s.hasMspt() || s.msptMean() < TICK_BUDGET_MS * 0.8) && s.tps1m() >= 19.0;

        // ── Sustained overload ────────────────────────────────────────────
        if (s.msptMean() >= TICK_BUDGET_MS) {
            out.add(new Culprit(90, "The server is over budget on every tick",
                    String.format("Average tick takes %.1fms; the budget for 20 TPS is %.0fms.",
                            s.msptMean(), TICK_BUDGET_MS),
                    "This is steady load, not spikes. Reduce entity and chunk counts: "
                  + "run /catalyst perf lag to find the worst chunks and /catalyst config check for config."));
        } else if (s.msptMean() >= TICK_BUDGET_MS * 0.8) {
            out.add(new Culprit(60, "Ticks are close to the limit",
                    String.format("Average tick is %.1fms of the %.0fms budget.",
                            s.msptMean(), TICK_BUDGET_MS),
                    "There is little headroom, so any extra load will drop TPS. "
                  + "Applying /catalyst config apply safe buys some back."));
        }

        // Without tick times, falling TPS is the only sign of overload there is.
        if (!s.hasMspt() && s.tps1m() < 19.0) {
            out.add(new Culprit(80, "The server is not keeping up",
                    String.format("TPS averaged %.1f over the last minute, short of 20.", s.tps1m()),
                    "spark cannot time ticks on this platform, so this says only that ticks run long. "
                  + "/catalyst perf profile names the code using them, and /catalyst perf lag the busiest chunks."));
        }

        // ── Spikes rather than steady load ────────────────────────────────
        // A 95th percentile far above the median means most ticks are fine and a few
        // are terrible, which is a different problem with different causes.
        if (s.mspt95th() > s.msptMedian() * 2.5 && s.mspt95th() > TICK_BUDGET_MS) {
            out.add(new Culprit(85, "Lag comes in spikes, not constant load",
                    String.format("Median tick %.1fms but the worst 5%% reach %.1fms, peaking at %.1fms.",
                            s.msptMedian(), s.mspt95th(), s.msptMax()),
                    "Steady optimizations will not fix this. Usual causes are garbage collection, "
                  + "chunk generation from players exploring, or a scheduled task. "
                  + "Check the garbage collection finding below if present."));
        }

        // ── Garbage collection ────────────────────────────────────────────
        if (s.gcAvgTimeMs() >= 50) {
            out.add(new Culprit(80, "Garbage collection pauses are long",
                    String.format("%s averages %.0fms per collection, every %.1fs.",
                            s.gcName(), s.gcAvgTimeMs(), s.gcAvgFrequencyMs() / 1000.0),
                    "Each pause freezes the server. Give the JVM more heap, or switch to "
                  + "Aikar's G1 flags, which are tuned to keep pauses short."));
        } else if (s.gcAvgFrequencyMs() > 0 && s.gcAvgFrequencyMs() < 10_000) {
            out.add(new Culprit(65, "Garbage collection is running constantly",
                    String.format("%s collects every %.1fs, averaging %.0fms.",
                            s.gcName(), s.gcAvgFrequencyMs() / 1000.0, s.gcAvgTimeMs()),
                    "Short pauses, but so frequent they add up. This usually means the heap "
                  + "is too small for the workload, or something is allocating heavily."));
        }

        // ── Competition from outside the server ───────────────────────────
        if (s.cpuSystem() - s.cpuProcess() > 0.30) {
            out.add(new Culprit(70, "Something else on this machine is using the CPU",
                    String.format("The whole system is at %.0f%% CPU but this server only accounts for %.0f%%.",
                            s.cpuSystem() * 100, s.cpuProcess() * 100),
                    "The bottleneck may not be Minecraft. Check what else runs on this host - "
                  + "other servers, backups, or a busy neighbour on shared hosting."));
        }

        // ── Work happening away from the tick loop ────────────────────────
        if (s.cpuProcess() > 0.70 && s.msptMean() < TICK_BUDGET_MS * 0.8) {
            out.add(new Culprit(55, "Heavy CPU use that is not slowing ticks",
                    String.format("This server uses %.0f%% CPU while ticks stay at %.1fms.",
                            s.cpuProcess() * 100, s.msptMean()),
                    "Work is happening off the main thread - chunk generation, or plugins doing "
                  + "async work. Harmless for TPS now, but it limits headroom."));
        }

        // ── Direction of travel ───────────────────────────────────────────
        if (s.tps5s() < s.tps15m() - 3.0) {
            out.add(new Culprit(75, "Performance is getting worse right now",
                    String.format("TPS over 5s is %.1f against %.1f over 15m.", s.tps5s(), s.tps15m()),
                    "Something started recently. Run /catalyst perf lag 30 immediately to catch it "
                  + "while it is still happening."));
        } else if (s.tps15m() < s.tps5s() - 3.0) {
            out.add(new Culprit(40, "Recovering from an earlier problem",
                    String.format("TPS is %.1f now but averaged %.1f over 15m.", s.tps5s(), s.tps15m()),
                    "The server is fine at the moment. Whatever caused it has passed, "
                  + "so treat older readings with suspicion."));
        }

        if (out.isEmpty() && healthy) {
            out.add(new Culprit(0, "Nothing looks wrong",
                    s.hasMspt()
                            ? String.format("Ticks average %.1fms of %.0fms, TPS %.1f over the last minute.",
                                    s.msptMean(), TICK_BUDGET_MS, s.tps1m())
                            : String.format("TPS %.1f over the last minute.", s.tps1m()),
                    "No action needed."));
        }

        out.sort((a, b) -> Integer.compare(b.confidence(), a.confidence()));

        return out;
    }
}

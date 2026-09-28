// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.util.Branding;

import java.util.*;

/**
 * Turns a benchmark into "what is most likely costing you, and the most obvious fix". Per-unit
 * cost (from the benchmark) times count (a census of the real worlds) estimates where the tick
 * goes; the estimates are ranked and paired with a fix. Pure logic, testable on its own.
 */
public final class Recommendations {
    /** What the real worlds hold, counted just before the benchmark started. */
    public record Census(int villagers, int otherMobs, int players, int loadedChunks, int hoppers, int droppedItems,
                         int minecarts) {}

    /**
     * Per-unit costs in ms/tick from the benchmark; null where that stage did not run.
     *
     * @param pendingFixes config changes the scan still recommends, by optimization id
     * @param mobLimiterOn whether Catalyst's mob limiter runtime module is already running
     */
    public record Inputs(double liveMs, Double perVillager, Double perMob, Double perClock,
                         Double perFootprintPlayer, Double perRealClient, int footprintChunks,
                         double chunkGenMs, Double perHopper, Double perItem, Double perMinecart, Double perExplosion,
                         Census census, Map<String, OptimizationLevel> pendingFixes,
                         boolean mobLimiterOn) {}

    /**
     * @param estimatedMs NaN for advice that has no estimate (it is listed after estimated ones)
     * @param command     a Catalyst command that applies the fix, or null
     */
    public record Item(String title, double estimatedMs, String evidence, String fix, String command) {}

    /** Below this an estimate is noise, not a cause. */
    static final double MEANINGFUL_MS = 0.5;

    private Recommendations() {}

    public static List<Item> build(Inputs in) {
        final List<Item> items = new ArrayList<>();
        final Census c = in.census();
        double explained = 0;

        if (c.villagers() > 0 && in.perVillager() != null) {
            final double ms = c.villagers() * in.perVillager();
            explained += ms;

            if (ms >= MEANINGFUL_MS)
                items.add(withFix("Villagers", ms,
                        String.format("%,d villagers x %.3fms each", c.villagers(), in.perVillager()),
                        in, List.of("spigot.tick-inactive-villagers", "paper.entity-behavior", "spigot.entity-activation-range"),
                        "Villagers are the most expensive mob. Cap trading halls and breeders, "
                                + "or keep villagers in 1x1 cells so they stop pathfinding."));
        }

        if (c.otherMobs() > 0 && in.perMob() != null) {
            final double ms = c.otherMobs() * in.perMob();
            explained += ms;

            if (ms >= MEANINGFUL_MS)
                items.add(withFix("Mobs", ms,
                        String.format("%,d mobs x %.3fms each (measured with cows)", c.otherMobs(), in.perMob()),
                        in, List.of("spigot.entity-activation-range", "bukkit.spawn-limits", "bukkit.ticks-per-spawn", "paper.pathfinding"),
                        in.mobLimiterOn()
                                ? "Find the farms holding them with /catalyst perf lag and add a kill chamber or a cap."
                                : "Turn on " + Branding.name() + "'s mob limiter (runtime.mob-limiter) to stop mobs piling up in one chunk."));
        }

        final Double perPlayer = in.perRealClient() != null ? in.perRealClient() : in.perFootprintPlayer();

        if (c.players() > 0 && perPlayer != null) {
            final double ms = c.players() * perPlayer;
            explained += ms;

            if (ms >= MEANINGFUL_MS)
                items.add(withFix("Players", ms,
                        String.format("%,d online x %.3fms each%s", c.players(), perPlayer,
                                in.perRealClient() != null ? " (measured with real clients)" : " (footprint estimate)"),
                        in, List.of("server.view-distance", "server.simulation-distance", "server.network-compression"),
                        "Players are the load you want. If they are the biggest cost, the server is doing its job."));
        }

        // Chunks ticking with nobody near them: spawn chunks, force-loaded chunks, chunk loaders.
        if (in.perFootprintPlayer() != null && in.footprintChunks() > 0) {
            final double perChunk = in.perFootprintPlayer() / in.footprintChunks();
            final int unattended = c.loadedChunks() - c.players() * in.footprintChunks();

            if (unattended > 0) {
                final double ms = unattended * perChunk;
                explained += ms;

                if (ms >= MEANINGFUL_MS)
                    items.add(new Item("Chunks loaded with no player near", ms,
                            String.format("~%,d chunks x %.4fms each", unattended, perChunk),
                            "Spawn chunks, force-loaded chunks and chunk-loader farms tick with nobody there. "
                                    + "Check /forceload query in each world.", null));
            }
        }

        if (!Double.isNaN(in.chunkGenMs()) && in.chunkGenMs() >= 15)
            items.add(new Item("Generating new terrain", Double.NaN,
                    String.format("%.1fms per new chunk", in.chunkGenMs()),
                    "Players exploring stall the server. Pregenerate the world with a pregenerator such as "
                            + "Chunky, and set a world border so nothing beyond it is ever generated.", null));

        // Explosions cannot be counted in advance, only priced; worth it only with a fix to offer.
        if (in.perExplosion() != null && in.pendingFixes().containsKey("paper.optimize-explosions"))
            items.add(withFix("Explosions", Double.NaN,

                    // Measured per explosion each second, so one explosion is 20 ticks of that.
                    String.format("~%.1fms per explosion in the test", in.perExplosion() * 20),
                    in, List.of("paper.optimize-explosions"), null));

        if (in.perClock() != null && in.pendingFixes().containsKey("paper.redstone"))
            items.add(withFix("Redstone", Double.NaN,
                    String.format("%.3fms per clock in the test", in.perClock()),
                    in, List.of("paper.redstone"), null));

        if (c.hoppers() > 0 && in.perHopper() != null) {
            final double ms = c.hoppers() * in.perHopper();
            explained += ms;

            if (ms >= MEANINGFUL_MS)
                items.add(withFix("Hoppers", ms,
                        String.format("%,d hoppers x %.4fms each", c.hoppers(), in.perHopper()),
                        in, List.of("paper.hopper-occlusion"),
                        "Large hopper chains tick constantly. Water streams or fewer, shorter chains cost far less."));
        } else if (c.hoppers() >= 500) {
            items.add(withFix("Hoppers", Double.NaN, String.format("%,d hoppers in loaded chunks", c.hoppers()),
                    in, List.of("paper.hopper-occlusion"),
                    "Large hopper chains tick constantly. Water streams or fewer, shorter chains cost far less."));
        }

        if (c.droppedItems() > 0 && in.perItem() != null) {
            final double ms = c.droppedItems() * in.perItem();
            explained += ms;

            if (ms >= MEANINGFUL_MS)
                items.add(withFix("Dropped items", ms,
                        String.format("%,d items on the ground x %.4fms each", c.droppedItems(), in.perItem()),
                        in, List.of("spigot.merge-radius"),
                        "Items pile up where a farm overflows or nobody collects. Find it with /catalyst perf lag,"
                                + " and let " + Branding.name() + "'s item merger (runtime.item-merger) combine them."));
        }

        if (c.minecarts() > 0 && in.perMinecart() != null) {
            final double ms = c.minecarts() * in.perMinecart();
            explained += ms;

            if (ms >= MEANINGFUL_MS)
                items.add(new Item("Minecarts", ms,
                        String.format("%,d minecarts x %.4fms each (measured going round a track)", c.minecarts(), in.perMinecart()),
                        "Carts left on tracks, piled up at stations or stuck in a cart farm all keep ticking, and a heap"
                                + " of them pushing each other costs more still. Clear out the ones nobody uses.", null));
        }

        final double unexplained = in.liveMs() - explained;

        if (unexplained >= 5 && unexplained >= in.liveMs() * 0.3)
            items.add(new Item("Unexplained load", unexplained,
                    String.format("%.1fms of your %.1fms tick is not covered above", unexplained, in.liveMs()),
                    "Plugins, a specific build, or something the benchmark does not model. "
                            + "/catalyst perf profile shows exactly what is using it.", "/catalyst perf profile"));

        items.removeIf(Objects::isNull);
        items.sort(Comparator.comparingDouble((Item i) -> Double.isNaN(i.estimatedMs()) ? -1 : i.estimatedMs()).reversed());

        return items;
    }

    /**
     * Pairs a cause with the pending Catalyst change that addresses it, preferring the
     * safest level. Falls back to the advice when every relevant setting is already done.
     */
    private static Item withFix(String title, double ms, String evidence, Inputs in, List<String> ids, String advice) {
        final List<String> pending = ids.stream().filter(in.pendingFixes()::containsKey).toList();

        if (pending.isEmpty())
            return advice == null ? null : new Item(title, ms, evidence, advice, null);

        // Offer exactly the settings for this cause, not a whole level, so one recommendation
        // applies on its own.
        return new Item(title, ms, evidence,
                Branding.name() + " has " + pending.size() + " pending change" + (pending.size() == 1 ? "" : "s")
                        + " for this (" + String.join(", ", pending) + ").",
                "/catalyst config fix " + String.join(" ", pending));
    }
}

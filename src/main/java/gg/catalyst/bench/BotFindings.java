// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import gg.catalyst.spark.ProfileAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Specific things the bot run measured: a plugin taking real tick time (with its costliest
 * listeners), chunk work eating the tick, terrain generation falling behind. Nothing below the
 * thresholds. No server access, so it's testable on its own.
 */
final class BotFindings {
    record Finding(String title, String evidence, String advice) {}

    /** A plugin must use at least this much of each tick to be named. */
    static final double PLUGIN_MS = 0.25;
    /** Listeners below this are not worth naming beside their plugin. */
    static final double LISTENER_MS = 0.05;
    /** Chunk work must take this much, and this share of the tick, to be called out. */
    static final double CHUNK_MS = 1.0, CHUNK_SHARE = 25;

    private BotFindings() {}

    /**
     * @param profile     spark's profile taken while the bots played, or null without spark
     * @param ignore      plugins never named (Catalyst itself, spark), lower case
     * @param mirrorAsked     chunks the bots caused that were also generated as real terrain
     * @param mirrorDone      how many of those finished
     * @param chunksGenerated chunks the server had to generate while the bots ran (new terrain)
     * @param chunksLoaded    all chunks that loaded while they ran (generated plus read from disk)
     */
    /** Entity tracking must take this much, and this share, to be called out. */
    static final double TRACK_MS = 3.0, TRACK_SHARE = 20;

    static List<Finding> find(ProfileAnalysis.Result profile, int bots, Set<String> ignore,
                              int mirrorAsked, int mirrorDone, int chunksGenerated, int chunksLoaded, boolean clustered) {
        final List<Finding> out = new ArrayList<>();

        if (profile != null) {
            final int ticks = Math.max(1, profile.ticks());

            for (final ProfileAnalysis.Share p : profile.plugins()) {
                if (p.perTick() < PLUGIN_MS || ignore.contains(p.name().toLowerCase(Locale.ROOT))) continue;
                final List<String> named = new ArrayList<>();
                final List<ProfileAnalysis.HotMethod> listeners = profile.listenersByPlugin().get(p.name());

                if (listeners != null)
                    for (final ProfileAnalysis.HotMethod l : listeners) {
                        final double ms = l.millis() / ticks;

                        if (ms < LISTENER_MS || named.size() >= 3) break;
                        named.add(String.format(Locale.ROOT, "%s %.2fms", l.frame(), ms));
                    }
                out.add(new Finding(p.name() + " took " + String.format(Locale.ROOT, "%.2fms", p.perTick())
                        + " of every tick with " + bots + " bots on",
                        named.isEmpty()
                                ? "none of it in an event listener, so likely a scheduled task"
                                : "most in " + String.join(", ", named),
                        "This runs for every player, so it grows with your player count. Check " + p.name()
                                + "'s settings for what those handlers do, or report it to its author."));
            }

            for (final ProfileAnalysis.Share c : profile.categories())
                if (c.name().equals("Chunk loading and generation") && c.perTick() >= CHUNK_MS && c.percent() >= CHUNK_SHARE) {
                    // The measured split, not the mode: only if a real share of the chunks were newly
                    // generated is pregenerating any use. Roaming already-built terrain generates
                    // almost nothing, so its cost is loading from disk and sending to the clients.
                    final boolean generating = chunksGenerated >= 50 && chunksGenerated * 4 >= chunksLoaded;
                    final String title = (generating ? "Loading and generating chunks took " : "Loading and sending chunks took ")
                            + String.format(Locale.ROOT, "%.1fms of every tick (%.0f%%)", c.perTick(), c.percent());
                    final String evidence = chunksLoaded > 0
                            ? String.format(Locale.ROOT, "with %d bots on: %,d of %,d chunks were newly generated, the rest read from disk",
                                    bots, chunksGenerated, chunksLoaded)
                            : "while " + bots + " bots played";
                    final String advice = generating
                            ? "Pregenerate the world (Chunky), set a world border, or lower view-distance."
                            : "The terrain already exists, so pregenerating will not help. Each player has a view's worth of"
                                    + " chunks loaded and sent to them; lower view-distance and simulation-distance to cut it.";
                    out.add(new Finding(title, evidence, advice));
                }

            // Only on a spread run, where tracking reflects real distributed load. On a clustered
            // run it is mostly the bots tracking each other - the report explains that already, and
            // lowering entity-tracking-range for it would be advice for a case real players never hit.
            if (!clustered)
                for (final ProfileAnalysis.Share c : profile.categories())
                    if (c.name().equals("Entity tracking") && c.perTick() >= TRACK_MS && c.percent() >= TRACK_SHARE)
                        out.add(new Finding(String.format(Locale.ROOT, "Tracking entities took %.1fms of every tick (%.0f%%)",
                                        c.perTick(), c.percent()),
                                "the server sends each player updates for every entity in range, so this grows with"
                                        + " players times nearby entities",
                                "Lower entity-tracking-range in spigot.yml so players are sent fewer distant entities,"
                                        + " and cull item and mob build-ups near where players gather."));
        }

        if (mirrorAsked >= 50 && mirrorDone < mirrorAsked * 0.9)
            out.add(new Finding("Terrain generation fell behind " + bots + " exploring bots",
                    String.format(Locale.ROOT, "only %,d of the %,d real-terrain chunks they needed were ready by the end",
                            mirrorDone, mirrorAsked),
                    "Players exploring new land would outrun generation too. Pregenerate the world (Chunky)"
                            + " and set a world border so nothing beyond it is ever generated."));

        return out;
    }
}

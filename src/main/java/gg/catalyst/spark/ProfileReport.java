// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import gg.catalyst.chat.ChatLinks;
import gg.catalyst.report.Rating;
import gg.catalyst.util.Branding;
import gg.catalyst.util.PluginInfo;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Writes a profile analysis to chat, most costly first, with places to go and look. */
public final class ProfileReport {
    private static final double TICK_BUDGET_MS = 50.0;

    /** Categories whose cost lives in mobs, so crowded chunks are the place to look. */
    private static final Set<String> MOB_CATEGORIES =
            Set.of("Mob AI", "Mob pathfinding", "Entity ticking", "Mob spawning");

    private ProfileReport() {}

    /**
     * Sends the full profile report and returns its lines. With echo off, nothing is sent.
     * {@code sparkUrl} is the uploaded profile's spark.lucko.me link, or null to keep the
     * local-file wording.
     */
    public static List<String> send(CommandSender to, ProfileAnalysis.Result r,
                                    List<WorldHotspots.Spot> crowded, List<WorldHotspots.Spot> hoppers,
                                    File profileFile, ChatLinks links, String sparkUrl, boolean echo) {
        final List<String> out = new ArrayList<>();

        if (!echo) to = null;
        sep(to);
        final Rating tick = forTick(r.mainBusyPerTick());
        emit(to, out, String.format(ChatColor.AQUA + "Profile report " + ChatColor.GRAY + "%.0fs across every thread", r.durationSeconds()));
        emit(to, out, String.format(ChatColor.GRAY + "  Game tick %s%.2fms " + ChatColor.GRAY + "per tick of %.0fms " + ChatColor.DARK_GRAY + "(%.0f%% of the budget)",
                tick.color, r.mainBusyPerTick(), TICK_BUDGET_MS, 100 * r.mainBusyPerTick() / TICK_BUDGET_MS));

        if (r.ticksEstimated())
            emit(to, out, ChatColor.DARK_GRAY + "  spark counts no ticks here, so this assumes 20 a second per region; treat it as approximate.");

        if (r.mainBusyPerTick() < 10)
            emit(to, out, ChatColor.GREEN + "  The tick has plenty of headroom - nothing here is overloading it.");

        emit(to, out, "");
        emit(to, out, ChatColor.WHITE + "  Where the tick goes");
        int shown = 0;

        for (final ProfileAnalysis.Share c : r.categories()) {
            if (c.percent() < 1.0 || shown >= 6) continue;
            shown++;
            final Rating rating = forCategory(c.perTick());
            emit(to, out, String.format("  %s%3.0f%%  " + ChatColor.WHITE + "%s  %s%.2fms" + ChatColor.GRAY + "/tick",
                    rating.color, c.percent(), c.name(), rating.color, c.perTick()));

            if (!c.advice().isEmpty()) emit(to, out, ChatColor.DARK_GRAY + "        " + c.advice());

            if (c.name().equals("Other server work") && c.percent() >= 15)
                for (final ProfileAnalysis.HotMethod m : r.otherParts().subList(0, Math.min(3, r.otherParts().size())))
                    if (m.percent() >= 2)
                        emit(to, out, String.format(ChatColor.DARK_GRAY + "        mostly %s %.2fms/tick",
                                m.frame(), m.millis() / Math.max(1, r.ticks())));

            // Only point at a place when that place is meaningfully part of the problem.
            if (rating == Rating.GOOD) continue;

            if (MOB_CATEGORIES.contains(c.name())) pointAt(to, out, crowded, links);
            else if (c.name().equals("Hoppers")) pointAt(to, out, hoppers, links);
            else if (c.name().equals("Redstone") || c.name().equals("Liquids and block ticks"))
                link(to, out, links, ChatColor.GRAY + "        Find it: ", ChatColor.AQUA + "[/catalyst perf lag 30]",
                        "/catalyst perf lag 30", "Sample which chunks are generating block updates",
                        ChatLinks.Click.RUN);
        }

        if (!r.plugins().isEmpty()) {
            emit(to, out, "");
            emit(to, out, ChatColor.WHITE + "  Plugins in the tick");

            for (final ProfileAnalysis.Share p : r.plugins().subList(0, Math.min(5, r.plugins().size())))
                pluginLine(to, out, links, p);
        } else {
            emit(to, out, ChatColor.DARK_GRAY + "  No plugin code showed up in the tick.");
        }

        if (!r.hotMethods().isEmpty()) {
            emit(to, out, "");
            emit(to, out, ChatColor.WHITE + "  Hottest code");

            for (final ProfileAnalysis.HotMethod m : r.hotMethods()) {
                final String method = String.format(ChatColor.GRAY + "  %3.0f%%  " + ChatColor.WHITE + "%s", m.percent(), m.frame());

                if (m.plugin() == null) emit(to, out, method);
                else hovered(to, out, links, method + " ", ChatColor.WHITE + "[" + m.plugin() + "]", "", m.plugin());
            }
        }

        if (!r.otherThreads().isEmpty() && r.otherThreads().get(0).busyPercent() >= 5) {
            emit(to, out, "");
            emit(to, out, ChatColor.WHITE + "  Busiest other threads");

            for (final ProfileAnalysis.ThreadLoad t : r.otherThreads()) {
                if (t.busyPercent() < 5) break;
                emit(to, out, String.format(ChatColor.GRAY + "  %3.0f%%  " + ChatColor.WHITE + "%s", t.busyPercent(), t.name()));
            }
        }

        emit(to, out, "");
        // The file name carries dots (a timestamp and the .sparkprofile extension) that some
        // clients auto-link into a bogus http:// address. Keep the full path in the saved report,
        // but in chat show only the dot-free folder and put the path behind a copy button.
        final String savedPath = "plugins/spark/" + profileFile.getName();
        out.add(ChatColor.DARK_GRAY + "  Saved locally: " + savedPath);
        if (to != null)
            links.send(to, ChatColor.DARK_GRAY + "  Saved locally in plugins/spark  ",
                    ChatColor.AQUA + "[copy the file path]", "", savedPath,
                    "Copies the profile's file path", ChatLinks.Click.COPY);

        // Show the URL bare, with no brackets touching it: a proper OPEN_URL click event
        // handles vanilla, and clients that auto-link raw URL text (they add http:// to any
        // URL-ish token) need a clean token - a leading "[" or trailing "]" gets swallowed
        // into the address and breaks the auto-link.
        if (sparkUrl != null)
            link(to, out, links, ChatColor.DARK_GRAY + "  Full interactive call tree:  ",
                    ChatColor.AQUA + sparkUrl,
                    sparkUrl, "Open the uploaded profile at spark.lucko.me", ChatLinks.Click.OPEN_URL);
        else
            link(to, out, links, ChatColor.DARK_GRAY + "  For the full call tree, drop that file onto  ",
                    ChatColor.AQUA + "spark.lucko.me",
                    "https://spark.lucko.me", "Opens spark.lucko.me - then drag the .sparkprofile file onto the page",
                    ChatLinks.Click.OPEN_URL);
        sep(to);

        return out;
    }

    /**
     * The plugin breakdown on its own, each plugin followed by the event listeners inside it
     * that cost the most. Answers "which plugin, and which of its handlers, is using the tick".
     * Returns its lines. With echo off, nothing is sent.
     */
    public static List<String> sendPlugins(CommandSender to, ProfileAnalysis.Result r, ChatLinks links, boolean echo) {
        final List<String> out = new ArrayList<>();

        if (!echo) to = null;
        sep(to);
        emit(to, out, String.format(ChatColor.AQUA + "Plugins in the tick " + ChatColor.GRAY + "%.0fs across every thread", r.durationSeconds()));

        if (r.ticksEstimated())
            emit(to, out, ChatColor.DARK_GRAY + "  spark counts no ticks here, so this assumes 20 a second per region; treat it as approximate.");

        if (r.plugins().isEmpty()) {
            emit(to, out, ChatColor.GREEN + "  No plugin code showed up in the tick.");
            sep(to);
            return out;
        }

        final int ticks = Math.max(1, r.ticks());
        boolean any = false;

        for (final ProfileAnalysis.Share p : r.plugins()) {
            if (p.perTick() < 0.01) continue;
            any = true;
            pluginLine(to, out, links, p);

            final List<ProfileAnalysis.HotMethod> listeners = r.listenersByPlugin().get(p.name());

            if (listeners == null || listeners.isEmpty()) {
                emit(to, out, ChatColor.DARK_GRAY + "       no event listeners in the tick - likely a scheduled task (see /catalyst perf profile)");
                continue;
            }
            int shown = 0;

            for (final ProfileAnalysis.HotMethod l : listeners) {
                final double perTick = l.millis() / ticks;

                if (perTick < 0.01 || shown >= 4) break;
                shown++;
                emit(to, out, String.format(ChatColor.DARK_GRAY + "       " + ChatColor.GRAY + "%.2fms" + ChatColor.DARK_GRAY + "/tick  " + ChatColor.GRAY + "%s", perTick, l.frame()));
            }
        }

        // Plugins ran, but none used a measurable slice, so no rows were printed above.
        if (!any) {
            emit(to, out, ChatColor.GREEN + "  No plugin used a measurable slice of the tick.");
            sep(to);
            return out;
        }

        emit(to, out, "");
        emit(to, out, ChatColor.DARK_GRAY + "  Listener time is charged to the outermost @EventHandler on the stack.");
        emit(to, out, ChatColor.DARK_GRAY + "  For categories, methods and where to look, run /catalyst perf profile.");
        sep(to);

        return out;
    }

    /** A null recipient means the report is being built for upload only. */
    private static void emit(CommandSender to, List<String> out, String msg) {
        if (to != null) to.sendMessage(msg);
        out.add(msg);
    }

    private static void link(CommandSender to, List<String> out, ChatLinks links,
                             String before, String label, String value, String hover, ChatLinks.Click click) {
        if (to != null) links.send(to, before, label, "", value, hover, click);
        out.add(before + label);
    }

    /** One plugin's share of the tick, its name hoverable for version and author. */
    private static void pluginLine(CommandSender to, List<String> out, ChatLinks links, ProfileAnalysis.Share p) {
        final Rating rating = forCategory(p.perTick());
        hovered(to, out, links,
                String.format("  %s%3.0f%%  ", rating.color, p.percent()),
                ChatColor.WHITE + p.name(),
                String.format("  %s%.2fms" + ChatColor.GRAY + "/tick", rating.color, p.perTick()),
                p.name());
    }

    private static void hovered(CommandSender to, List<String> out, ChatLinks links,
                                String before, String label, String after, String pluginName) {
        if (to != null) links.hover(to, before, label, after, PluginInfo.hover(pluginName));
        out.add(before + label + after);
    }

    private static void sep(CommandSender s) { if (s != null) s.sendMessage(Branding.SEP); }

    private static void pointAt(CommandSender to, List<String> out, List<WorldHotspots.Spot> spots, ChatLinks links) {
        for (final WorldHotspots.Spot spot : spots.subList(0, Math.min(2, spots.size()))) {
            final String tp = "/tp " + spot.blockX() + " ~ " + spot.blockZ();
            link(to, out, links,
                    String.format(ChatColor.GRAY + "        Chunk %d, %d in %s holds " + ChatColor.WHITE + "%d " + ChatColor.GRAY + "%s  ",
                            spot.chunkX(), spot.chunkZ(), spot.world(), spot.count(), spot.what()),
                    ChatColor.AQUA + "[" + tp + "]", tp, "Teleport to the middle of this chunk", ChatLinks.Click.RUN);
        }
    }

    /** Whole-tick health: a tick near the 50ms budget has no room left for spikes. */
    private static Rating forTick(double millis) {
        if (millis < 25) return Rating.GOOD;

        if (millis < 45) return Rating.FAIR;

        return Rating.POOR;
    }

    /** One activity's share of the budget. Past 10ms, a single cause is a fifth of the tick. */
    private static Rating forCategory(double millis) {
        if (millis < 2) return Rating.GOOD;

        if (millis < 10) return Rating.FAIR;

        return Rating.POOR;
    }
}

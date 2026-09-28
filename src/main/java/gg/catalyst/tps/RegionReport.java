// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import gg.catalyst.chat.ChatLinks;
import gg.catalyst.report.Rating;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Writes Folia's per-region tick times to chat, slowest region first. */
public final class RegionReport {
    private RegionReport() {}

    /**
     * @param detailed also show the slowest region's TPS windows and tick times, and the
     *                 average over all regions
     */
    public static void send(CommandSender to, List<FoliaTicks.Region> regions, ChatLinks links, boolean detailed) {
        if (regions.isEmpty()) {
            to.sendMessage(ChatColor.GRAY + "  Regions " + ChatColor.WHITE + "none " + ChatColor.DARK_GRAY + "- no chunks are loaded, so nothing is ticking right now.");
            return;
        }

        final FoliaTicks.Region slow = regions.get(0);
        to.sendMessage(regions.size() == 1
                ? ChatColor.GRAY + "  Regions " + ChatColor.WHITE + "1 " + ChatColor.DARK_GRAY + "- all loaded chunks tick together"
                : String.format(ChatColor.GRAY + "  Regions " + ChatColor.WHITE + "%d " + ChatColor.DARK_GRAY + "each ticking on its own; the slowest is shown", regions.size()));

        if (detailed) {
            to.sendMessage(String.format(ChatColor.GRAY + "  TPS  %s%.1f " + ChatColor.DARK_GRAY + "now  %s%.1f " + ChatColor.DARK_GRAY + "1m  %s%.1f " + ChatColor.DARK_GRAY + "15m",
                    Rating.forTps(slow.tps5s()).color, slow.tps5s(),
                    Rating.forTps(slow.tps1m()).color, slow.tps1m(),
                    Rating.forTps(slow.tps15m()).color, slow.tps15m()));
            to.sendMessage(String.format(ChatColor.GRAY + "  Tick %s%.1fms " + ChatColor.DARK_GRAY + "avg  " + ChatColor.GRAY + "%.1fms " + ChatColor.DARK_GRAY + "median  " + ChatColor.GRAY + "%.1fms " + ChatColor.DARK_GRAY + "worst 5%%  " + ChatColor.GRAY + "%.1fms " + ChatColor.DARK_GRAY + "peak",
                    forTick(slow.msptAvg()).color, slow.msptAvg(), slow.msptMedian(), slow.mspt95th(), slow.msptMax()));
        }

        final String tp = teleport(slow);
        links.send(to, String.format(ChatColor.DARK_GRAY + "    %d chunk%s around chunk %d, %d in %s  ",
                        slow.chunks(), slow.chunks() == 1 ? "" : "s", slow.chunkX(), slow.chunkZ(), slow.world().getName()),
                ChatColor.AQUA + "[" + tp + "]", "", tp, "Teleport to the middle of this region", ChatLinks.Click.RUN);

        if (detailed && regions.size() > 1) {
            double tps = 0, mspt = 0;

            for (final FoliaTicks.Region r : regions) { tps += r.tps1m(); mspt += r.msptAvg(); }
            to.sendMessage(String.format(ChatColor.DARK_GRAY + "  Average over all %d regions: %.1f TPS, %.1fms a tick",
                    regions.size(), tps / regions.size(), mspt / regions.size()));
        }
    }

    /**
     * Plain /tp stays in the clicker's own world, so name the region's world. Older APIs have no
     * world key; there the plain form is the best on offer.
     */
    private static String teleport(FoliaTicks.Region r) {
        final String tp = "tp @s " + r.blockX() + " ~ " + r.blockZ();

        try {
            return "/execute in " + r.world().getKey() + " run " + tp;
        } catch (LinkageError noKey) {
            return "/tp " + r.blockX() + " ~ " + r.blockZ();
        }
    }

    /** Matches the colouring of tick times elsewhere: a tick near 50ms has no room for spikes. */
    private static Rating forTick(double millis) {
        if (millis < 25) return Rating.GOOD;

        if (millis < 45) return Rating.FAIR;

        return Rating.POOR;
    }
}

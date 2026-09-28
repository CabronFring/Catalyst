// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * The tick rate dropped below the watchdog's threshold. Fired once per incident (same rate limit
 * as the log line), on the main thread, so a plugin can react to lag - pause spawns, shed load,
 * ping staff. Nothing to cancel; the drop already happened.
 */
public final class CatalystLowTpsEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final double tps;
    private final double threshold;
    private final String region;

    public CatalystLowTpsEvent(double tps, double threshold, String region) {
        this.tps = tps;
        this.threshold = threshold;
        this.region = region == null ? "" : region;
    }

    public double getTps() { return this.tps; }

    public double getThreshold() { return this.threshold; }

    /** On Folia, which region the reading came from (e.g. "the region around 100, -200 in world"); empty elsewhere. */
    public String getRegion() { return this.region; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}

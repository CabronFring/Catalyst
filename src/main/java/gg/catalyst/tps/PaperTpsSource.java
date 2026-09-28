// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import gg.catalyst.platform.PlatformDetector;
import org.bukkit.Bukkit;

/** Paper 1.x and Folia, which both expose Server#getTPS. */
final class PaperTpsSource implements TpsSource {
    @Override
    public double currentTps() {
        try {
            final double[] tps = Bukkit.getTPS();
            return tps.length > 0 ? tps[0] : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    @Override
    public String describe() {
        // Folia ticks regions independently, so a single figure cannot mean what it
        // means elsewhere. Saying so beats quietly reporting a misleading number.
        return PlatformDetector.isFolia()
                ? "server API (Folia ticks regions separately, so treat this as indicative)"
                : "server API";
    }
}

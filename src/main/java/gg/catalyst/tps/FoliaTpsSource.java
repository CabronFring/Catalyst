// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import java.util.List;

/**
 * Folia's TPS, taken from the slowest region. A server-wide figure would let one lagging
 * region hide behind many idle ones, and that region is the one players are feeling.
 */
final class FoliaTpsSource implements TpsSource {
    private final PaperTpsSource fallback = new PaperTpsSource();

    @Override
    public double currentTps() {
        final List<FoliaTicks.Region> regions = FoliaTicks.regions();

        if (regions == null) return this.fallback.currentTps();

        // No regions means nothing is loaded, so nothing can be behind.
        if (regions.isEmpty()) return 20.0;
        final double tps = regions.get(0).tps1m();

        return Double.isNaN(tps) ? 20.0 : tps;
    }

    @Override
    public String describe() {
        return FoliaTicks.regions() == null ? this.fallback.describe() : "slowest Folia region, 1 minute average";
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import gg.catalyst.util.Branding;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Catalyst's own tick-rate measurement, the last resort when neither the API nor the internals
 * give a number. A task runs every 20 ticks and measures the wall-clock time that passed - one
 * second when healthy, longer when lagging - averaged over about a minute. Only used on plain
 * CraftBukkit; Folia reports TPS through its own API long before reaching this.
 */
final class MeasuredTpsSource implements TpsSource {
    private static final int SAMPLE_TICKS = 20;
    private static final int WINDOW = 60;

    private final double[] samples = new double[WINDOW];
    private int filled = 0;
    private int next = 0;
    private long lastNanos = 0;

    MeasuredTpsSource(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::sample, SAMPLE_TICKS, SAMPLE_TICKS);
    }

    private synchronized void sample() {
        final long now = System.nanoTime();

        if (this.lastNanos != 0) {
            final double seconds = (now - this.lastNanos) / 1_000_000_000.0;

            if (seconds > 0) {
                this.samples[this.next] = Math.min(20.0, SAMPLE_TICKS / seconds);
                this.next = (this.next + 1) % WINDOW;

                if (this.filled < WINDOW) this.filled++;
            }
        }
        this.lastNanos = now;
    }

    @Override
    public synchronized double currentTps() {
        if (this.filled == 0) return Double.NaN;
        double sum = 0;

        for (int i = 0; i < this.filled; i++) sum += this.samples[i];

        return sum / this.filled;
    }

    @Override
    public String describe() {
        return this.filled == 0 ? "measured by " + Branding.name() + " (warming up)" : "measured by " + Branding.name();
    }

    /** Available as soon as the first sample lands, about a second after enable. */
    @Override
    public boolean isAvailable() {
        return true;
    }
}

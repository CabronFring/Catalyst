// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Average tick duration over the last hundred ticks. TPS caps at 20 and hides everything under
 * the 50ms budget, so a benchmark needs the tick time itself. Paper exposes it via getTickTimes;
 * elsewhere the same hundred-long array is found by reflection, by its shape.
 */
final class TickMeter {
    private final Object source;
    private final Field field;
    private final boolean paper;

    private TickMeter(Object source, Field field, boolean paper) {
        this.source = source;
        this.field = field;
        this.paper = paper;
    }

    static TickMeter create() {
        try {
            Bukkit.getServer().getClass().getMethod("getTickTimes");
            return new TickMeter(null, null, true);
        } catch (NoSuchMethodException notPaper) {
            // Falls through to reflection.
        }

        try {
            final Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            final Object server = getServer.invoke(Bukkit.getServer());

            for (Class<?> c = server.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (final Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != long[].class) continue;
                    f.setAccessible(true);

                    if (((long[]) f.get(server)).length == 100) return new TickMeter(server, f, false);
                }
            }
        } catch (Throwable ignored) {
            // No usable source.
        }

        return null;
    }

    private long[] read() {
        try {
            return this.paper ? Bukkit.getServer().getTickTimes() : (long[]) this.field.get(this.source);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The longest of the last hundred ticks, in milliseconds: what players feel as a stutter. */
    double worstMillis() {
        final long[] nanos = this.read();

        if (nanos == null) return Double.NaN;
        long worst = 0;

        for (final long n : nanos) worst = Math.max(worst, n);

        return worst == 0 ? Double.NaN : worst / 1_000_000.0;
    }

    double averageMillis() {
        final long[] nanos = this.read();

        if (nanos == null) return Double.NaN;
        long total = 0;
        int counted = 0;

        for (final long n : nanos) if (n > 0) { total += n; counted++; }

        return counted == 0 ? Double.NaN : (total / (double) counted) / 1_000_000.0;
    }
}

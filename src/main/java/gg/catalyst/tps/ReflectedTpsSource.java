// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * TPS read out of server internals where the API lacks it. Field names vary by mapping, so
 * fields are found by shape: Spigot's double[3] of TPS, or vanilla's long[100] of tick nanos.
 */
final class ReflectedTpsSource implements TpsSource {
    private final Object server;
    private final Field recentTps;
    private final Field tickTimes;

    private ReflectedTpsSource(Object server, Field recentTps, Field tickTimes) {
        this.server = server;
        this.recentTps = recentTps;
        this.tickTimes = tickTimes;
    }

    /** Returns a working source, or null when no usable field could be found. */
    static ReflectedTpsSource tryCreate() {
        try {
            final Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            final Object minecraftServer = getServer.invoke(Bukkit.getServer());

            if (minecraftServer == null) return null;

            Field tps = null, ticks = null;

            for (Class<?> c = minecraftServer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (final Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;

                    try {
                        f.setAccessible(true);
                    } catch (RuntimeException inaccessible) {
                        continue;
                    }
                    final Object value = f.get(minecraftServer);

                    if (tps == null && value instanceof double[] d && d.length == 3 && looksLikeTps(d)) tps = f;

                    if (ticks == null && value instanceof long[] l && l.length == 100) ticks = f;
                }
            }

            if (tps == null && ticks == null) return null;

            final ReflectedTpsSource source = new ReflectedTpsSource(minecraftServer, tps, ticks);
            return Double.isNaN(source.currentTps()) ? null : source;
        } catch (Throwable notAvailable) {
            return null;
        }
    }

    private static boolean looksLikeTps(double[] values) {
        for (final double v : values) if (!(v > 0 && v <= 21)) return false;

        return true;
    }

    @Override
    public double currentTps() {
        try {
            if (this.recentTps != null) {
                final double[] values = (double[]) this.recentTps.get(this.server);

                if (values != null && values.length > 0) return Math.min(20.0, values[0]);
            }

            if (this.tickTimes != null) {
                final long[] nanos = (long[]) this.tickTimes.get(this.server);
                long total = 0;
                int counted = 0;

                for (final long n : nanos) if (n > 0) { total += n; counted++; }

                if (counted == 0) return Double.NaN;
                final double meanMillis = (total / (double) counted) / 1_000_000.0;

                // The tick loop sleeps to hold 20 TPS, so TPS only drops once a tick
                // takes longer than its 50ms slot.
                return Math.min(20.0, 1000.0 / Math.max(50.0, meanMillis));
            }
        } catch (Throwable ignored) {
            // Falls through to unavailable.
        }

        return Double.NaN;
    }

    @Override
    public String describe() {
        return this.recentTps != null ? "server internals" : "server internals (from tick durations)";
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;

/**
 * Spigot's TPS, on Bukkit.spigot(). Reflection because paper-api dropped the method from its
 * Spigot type, so it won't compile, though it's there at runtime on real Spigot.
 */
final class SpigotTpsSource implements TpsSource {
    private final Object spigotHandle;
    private final Method getTps;

    SpigotTpsSource(Object spigotHandle, Method getTps) {
        this.spigotHandle = spigotHandle;
        this.getTps = getTps;
    }

    /** Returns a working source, or null when this server has no Spigot TPS method. */
    static SpigotTpsSource tryCreate() {
        try {
            final Method spigot = Bukkit.class.getMethod("spigot");
            final Object handle = spigot.invoke(null);

            if (handle == null) return null;

            final Method getTps = handle.getClass().getMethod("getTPS");
            getTps.setAccessible(true);
            return new SpigotTpsSource(handle, getTps);
        } catch (Throwable notSpigot) {
            return null;
        }
    }

    @Override
    public double currentTps() {
        try {
            final Object result = this.getTps.invoke(this.spigotHandle);

            if (result instanceof double[] tps && tps.length > 0) return tps[0];
            return Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    @Override
    public String describe() {
        return "Spigot API";
    }
}

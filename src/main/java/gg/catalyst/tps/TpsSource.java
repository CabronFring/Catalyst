// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import gg.catalyst.platform.PlatformDetector;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * Reading TPS is not portable: Paper and Folia expose Server#getTPS, Spigot hides it
 * behind Bukkit.spigot(), and plain CraftBukkit has no API at all. Each reader lives in
 * its own class so the JVM never verifies one referencing a missing method. Tried most
 * trustworthy first: API, then internals by reflection, then timing ticks ourselves.
 */
public interface TpsSource {
    /** Most recent TPS, or Double.NaN while no reading exists yet. */
    double currentTps();

    /** Where the number came from, so reports can be honest about its meaning. */
    String describe();

    default boolean isAvailable() {
        return !Double.isNaN(currentTps());
    }

    static TpsSource create(Plugin plugin) {
        if (PlatformDetector.isFolia() && FoliaTicks.regions() != null)
            return new FoliaTpsSource();

        if (hasNoArgMethod(Server.class, "getTPS"))
            return new PaperTpsSource();

        final SpigotTpsSource spigot = SpigotTpsSource.tryCreate();

        if (spigot != null) return spigot;

        final ReflectedTpsSource reflected = ReflectedTpsSource.tryCreate();

        if (reflected != null) return reflected;

        return new MeasuredTpsSource(plugin);
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException | RuntimeException e) {
            return false;
        }
    }
}

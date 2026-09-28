// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.tps;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Per-region tick times on Folia, which has no single server tick - each region of loaded chunks
 * ticks on its own, and a server-wide average hides one laggy region. Reads each region's tick
 * report (the data Folia's /tps and spark use). Folia's API only gives TPS at a location, so this
 * reflects the internals by name (the report classes moved package between versions); if any is
 * missing, regions() returns null and callers fall back to the server figure.
 */
public final class FoliaTicks {
    /** One region, placed at its centre chunk. Tick times are in milliseconds, over a minute. */
    public record Region(World world, int chunkX, int chunkZ, int chunks,
                         double tps5s, double tps1m, double tps15m,
                         double msptAvg, double msptMedian, double mspt95th, double msptMax) {
        public int blockX() { return (chunkX << 4) + 8; }
        public int blockZ() { return (chunkZ << 4) + 8; }
    }

    /** Slowest first: lowest TPS, then the longest ticks, so the region worth visiting leads. */
    public static final Comparator<Region> SLOWEST_FIRST =
            Comparator.comparingDouble(Region::tps1m).thenComparing(Comparator.comparingDouble(Region::msptAvg).reversed());

    private static volatile boolean broken;

    private FoliaTicks() {}

    /**
     * Every region currently ticking, slowest first. Empty when nothing is loaded, which on
     * Folia means nothing is ticking. Null when the internals could not be read.
     */
    public static List<Region> regions() {
        if (broken) return null;

        try {
            final List<Region> out = new ArrayList<>();
            final long now = System.nanoTime();

            for (final World world : Bukkit.getWorlds()) {
                final Object level = call(world, "getHandle");
                final Object regioniser = level.getClass().getField("regioniser").get(level);
                final List<Object> found = new ArrayList<>();
                final Consumer<Object> collect = found::add;
                call(regioniser, "computeForAllRegions", Consumer.class, collect);

                for (final Object region : found) {
                    try {
                        final Region r = read(world, region, now);

                        if (r != null) out.add(r);
                    } catch (InvocationTargetException passing) {
                        // Folia itself threw on this one region, say mid-merge. The internals are
                        // fine, so skip it this time rather than switching off for good.
                    }
                }
            }
            out.sort(SLOWEST_FIRST);
            return out;
        } catch (Throwable t) {
            // Internals changed. Stay out of the way from now on rather than failing each call.
            broken = true;
            Bukkit.getLogger().info("[Catalyst] Folia region tick times are unreadable on this build ("
                    + t.getClass().getSimpleName() + "); using the server's own TPS instead.");
            return null;
        }
    }

    /** The slowest region, or null when there are none. */
    public static Region slowest(List<Region> regions) {
        return regions == null || regions.isEmpty() ? null : regions.get(0);
    }

    private static Region read(World world, Object region, long now) throws ReflectiveOperationException {
        final Object handle = call(call(region, "getData"), "getRegionSchedulingHandle");
        final Object r5s = report(handle, "getTickReport5s", now);
        final Object r1m = report(handle, "getTickReport1m", now);
        final Object r15m = report(handle, "getTickReport15m", now);

        // A region formed this instant has no ticks recorded yet; it has nothing to say.
        if (r1m == null) return null;

        final Object center = call(region, "getCenterChunk");

        // Null while a region briefly owns no chunks, as they unload; it has no place to show.
        if (center == null) return null;
        final int chunks = ((Collection<?>) call(region, "getOwnedChunks")).size();

        // Tick times are kept in nanoseconds.
        final Object perTick = call(r1m, "timePerTickData");
        final Object all = call(perTick, "segmentAll");

        return new Region(world, coordinate(center, "x"), coordinate(center, "z"), chunks,
                tps(r5s), tps(r1m), tps(r15m),
                number(all, "average") / 1e6, number(all, "median") / 1e6,
                number(call(perTick, "segment5PercentWorst"), "average") / 1e6,
                number(all, "greatest") / 1e6);
    }

    private static Object report(Object handle, String name, long now) throws ReflectiveOperationException {
        return handle.getClass().getMethod(name, long.class).invoke(handle, now);
    }

    private static double tps(Object report) throws ReflectiveOperationException {
        if (report == null) return Double.NaN;

        return number(call(call(report, "tpsData"), "segmentAll"), "average");
    }

    private static double number(Object target, String name) throws ReflectiveOperationException {
        return ((Number) call(target, name)).doubleValue();
    }

    /** ChunkPos has public x/z fields today and record accessors in newer versions; take either. */
    private static int coordinate(Object pos, String name) throws ReflectiveOperationException {
        try {
            return pos.getClass().getField(name).getInt(pos);
        } catch (NoSuchFieldException e) {
            return ((Number) call(pos, name)).intValue();
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        final Method m = target.getClass().getMethod(name);

        return m.invoke(target);
    }

    private static Object call(Object target, String name, Class<?> param, Object arg) throws ReflectiveOperationException {
        return target.getClass().getMethod(name, param).invoke(target, arg);
    }
}

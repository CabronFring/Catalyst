// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import java.util.*;

/**
 * Routes for bots roaming a real world. The bot client never reads the world - it only
 * sends positions - so the server, which knows every block, plans where each bot walks: a
 * breadth-first search over the blocks a player can stand on, stepping up one block (a
 * jump), dropping up to three, and never through liquid or anything a player would set
 * off or be hurt by. A route ends at a random spot some distance away, within a leash of
 * where the run started, so the bots wander around the player who started it.
 */
final class RoamPaths {
    /** What the search needs to know about the world, so it can be tested without a server. */
    interface Terrain {
        /**
         * The height a player's feet rest at when standing in block x,y,z - the top of the
         * block below, which is under 1 for a slab - or NaN when nobody can stand there.
         */
        double standAt(int x, int y, int z);

        /** Whether a player's body can be in block x,y,z: passable, not liquid, not harmful. */
        boolean open(int x, int y, int z);
    }

    /**
     * A waypoint: the middle of a block, at the height a player's feet rest there, and
     * whether there is room overhead to jump there - a jump lifts the body 1.25 blocks, and
     * the bot cannot see a ceiling, so it only jumps where it has been told there is none.
     */
    record Point(double x, double y, double z, boolean roomToJump) {}

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Tried in this order, so a route keeps to its level when it can. */
    private static final int[] RISES = {0, 1, -1, -2, -3};

    private RoamPaths() {}

    /**
     * A walk from block (x, y, z) to a random spot about {@code distance} blocks away, no
     * further than {@code leash} from (cx, cz). Empty when the bot cannot stand where it is
     * or has nowhere to go. {@code budget} caps the blocks examined, which bounds the cost.
     */
    static List<Point> wander(Terrain t, int x, int y, int z, int cx, int cz, int distance, int leash,
                              Random random, int budget) {
        if (Double.isNaN(t.standAt(x, y, z))) return List.of();
        final Map<Long, Long> cameFrom = new HashMap<>();
        final Map<Long, Double> height = new HashMap<>();
        final ArrayDeque<Long> queue = new ArrayDeque<>();
        final long start = key(x, y, z);
        cameFrom.put(start, start);
        height.put(start, t.standAt(x, y, z));
        queue.add(start);
        final List<Long> far = new ArrayList<>(), any = new ArrayList<>();

        while (!queue.isEmpty() && cameFrom.size() < budget) {
            final long at = queue.poll();
            final int ax = bx(at), ay = by(at), az = bz(at);
            final double here = height.get(at);

            for (final int[] side : SIDES) {
                final int nx = ax + side[0], nz = az + side[1];

                // Outside the leash only on the way back in: a bot knocked past it must still
                // have somewhere to go.
                final int out = sq(nx - cx) + sq(nz - cz);

                if (out > sq(leash) && out >= sq(ax - cx) + sq(az - cz)) continue;

                for (final int rise : RISES) {
                    final int ny = ay + rise;
                    final double stand = step(t, ax, ay, az, nx, ny, nz, rise, here);

                    if (Double.isNaN(stand)) continue;
                    final long next = key(nx, ny, nz);

                    if (cameFrom.putIfAbsent(next, at) == null) {
                        height.put(next, stand);
                        queue.add(next);
                        any.add(next);

                        if (sq(nx - x) + sq(nz - z) >= sq(distance)) far.add(next);
                    }
                    break;
                }
            }
        }
        final List<Long> goals = far.isEmpty() ? any : far;

        if (goals.isEmpty()) return List.of();
        final LinkedList<Point> route = new LinkedList<>();

        for (long at = goals.get(random.nextInt(goals.size())); at != start; at = cameFrom.get(at))
            route.addFirst(new Point(bx(at) + 0.5, height.get(at), bz(at) + 0.5,
                    t.open(bx(at), by(at) + 2, bz(at)) && t.open(bx(at), by(at) + 3, bz(at))));

        return route;
    }

    /**
     * The height at which a player can step from (ax, ay, az) to (nx, ny, nz), or NaN. Up a
     * level is a jump, so it needs headroom above the start as well; going down, the space
     * over the edge must be open all the way.
     */
    private static double step(Terrain t, int ax, int ay, int az, int nx, int ny, int nz, int rise, double from) {
        final double stand = t.standAt(nx, ny, nz);

        if (Double.isNaN(stand) || !t.open(nx, ny, nz) || !t.open(nx, ny + 1, nz)) return Double.NaN;

        if (stand - from > 1.25) return Double.NaN;

        // A climb is a jump: room for it over the start, and for the body landing on the block.
        if (rise > 0 && (!t.open(ax, ay + 2, az) || !t.open(ax, ay + 3, az) || !t.open(nx, ny + 2, nz))) return Double.NaN;

        if (rise < 0)
            for (int yy = ny + 2; yy <= ay + 1; yy++)
                if (!t.open(nx, yy, nz)) return Double.NaN;

        return stand;
    }

    private static int sq(int v) { return v * v; }

    // A block position packed into a long: 26 bits each for x and z, 12 for y.
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int bx(long k) { return (int) (k >> 38); }
    private static int bz(long k) { return (int) (k << 26 >> 38); }
    private static int by(long k) { return (int) (k << 52 >> 52); }
}

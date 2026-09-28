// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoamPathsTest {
    /**
     * A test world: solid ground whose top is at ground(x, z), air above it, except blocks
     * listed as walls (solid) or pool (liquid, never entered).
     */
    private static final class Grid implements RoamPaths.Terrain {
        final Set<String> walls = new HashSet<>(), pool = new HashSet<>();
        final java.util.function.IntBinaryOperator ground;

        Grid(java.util.function.IntBinaryOperator ground) { this.ground = ground; }

        boolean solid(int x, int y, int z) {
            return y < this.ground.applyAsInt(x, z) || this.walls.contains(x + "," + y + "," + z);
        }

        @Override
        public double standAt(int x, int y, int z) {
            return this.solid(x, y - 1, z) && !this.pool.contains(x + "," + (y - 1) + "," + z) ? y : Double.NaN;
        }

        @Override
        public boolean open(int x, int y, int z) {
            return !this.solid(x, y, z) && !this.pool.contains(x + "," + y + "," + z);
        }
    }

    private static void assertWalkable(List<RoamPaths.Point> route, double startY) {
        double y = startY;

        for (final RoamPaths.Point p : route) {
            assertTrue(p.y() - y <= 1.0 + 1e-9, "never climbs more than one block at a time: " + route);
            assertTrue(y - p.y() <= 3.0 + 1e-9, "never drops more than three blocks: " + route);
            y = p.y();
        }
    }

    @Test
    void wandersAcrossFlatGroundToASpotFarAway() {
        final Grid flat = new Grid((x, z) -> 64);
        final List<RoamPaths.Point> route = RoamPaths.wander(flat, 0, 64, 0, 0, 0, 10, 30, new Random(1), 5000);
        assertFalse(route.isEmpty());
        final RoamPaths.Point end = route.get(route.size() - 1);
        assertTrue(Math.hypot(end.x() - 0.5, end.z() - 0.5) >= 9.5, "ends about the distance away");

        for (final RoamPaths.Point p : route) assertEquals(64, p.y(), 1e-9);
    }

    @Test
    void stepsUpOneBlockButNotTwo() {
        // Ground rises by one at x = 3, and by two more at x = 6: the second rise is a wall.
        final Grid steps = new Grid((x, z) -> x >= 6 ? 67 : x >= 3 ? 65 : 64);

        for (int seed = 0; seed < 20; seed++) {
            final List<RoamPaths.Point> route = RoamPaths.wander(steps, 0, 64, 0, 0, 0, 8, 20, new Random(seed), 5000);
            assertWalkable(route, 64);

            for (final RoamPaths.Point p : route) assertTrue(p.x() < 6, "the two-block rise is never climbed");
        }
    }

    @Test
    void dropsUpToThreeBlocks() {
        // A two-block platform three blocks up: everywhere else is down the drop.
        final Grid ledge = new Grid((x, z) -> z == 0 && (x == 0 || x == 1) ? 64 : 61);
        final List<RoamPaths.Point> route = RoamPaths.wander(ledge, 0, 64, 0, 0, 0, 6, 20, new Random(3), 5000);
        assertWalkable(route, 64);
        assertEquals(61, route.get(route.size() - 1).y(), 1e-9, "ends down the three-block drop");
    }

    @Test
    void neverWalksIntoLiquid() {
        final Grid lake = new Grid((x, z) -> 64);

        for (int x = -2; x <= 2; x++)
            for (int z = 3; z <= 6; z++) {
                lake.pool.add(x + ",63," + z);
                lake.pool.add(x + ",64," + z);
            }

        for (int seed = 0; seed < 20; seed++)
            for (final RoamPaths.Point p : RoamPaths.wander(lake, 0, 64, 0, 0, 0, 8, 20, new Random(seed), 5000))
                assertFalse(lake.pool.contains((int) Math.floor(p.x()) + ",64," + (int) Math.floor(p.z())), "stays out of the lake");
    }

    @Test
    void staysOnTheLeash() {
        final Grid flat = new Grid((x, z) -> 64);

        for (int seed = 0; seed < 20; seed++)
            for (final RoamPaths.Point p : RoamPaths.wander(flat, 0, 64, 0, 0, 0, 30, 12, new Random(seed), 5000))
                assertTrue(Math.hypot(p.x() - 0.5, p.z() - 0.5) <= 13, "within the leash of the centre");
    }

    @Test
    void aBotKnockedPastTheLeashIsLedBackIn() {
        final Grid flat = new Grid((x, z) -> 64);

        for (int seed = 0; seed < 20; seed++) {
            final List<RoamPaths.Point> route = RoamPaths.wander(flat, 20, 64, 0, 0, 0, 8, 12, new Random(seed), 5000);
            assertFalse(route.isEmpty(), "has a way back");
            double last = 20.5;

            for (final RoamPaths.Point p : route) {
                final double from = Math.hypot(p.x() - 0.5, p.z() - 0.5);
                assertTrue(from <= 13 || from < last, "outside the leash only while heading in: " + route);
                last = from;
            }
        }
    }

    @Test
    void goesNowhereWhenBoxedIn() {
        final Grid box = new Grid((x, z) -> 64);

        for (final int[] side : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            for (int y = 64; y <= 66; y++) box.walls.add(side[0] + "," + y + "," + side[1]);
        assertTrue(RoamPaths.wander(box, 0, 64, 0, 0, 0, 5, 20, new Random(0), 5000).isEmpty());
    }

    @Test
    void cannotStartInMidAir() {
        final Grid flat = new Grid((x, z) -> 64);
        assertTrue(RoamPaths.wander(flat, 0, 70, 0, 0, 0, 5, 20, new Random(0), 5000).isEmpty());
    }
}

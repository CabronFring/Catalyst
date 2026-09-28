// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Against a real profile spark wrote on the Paper 1.21.11 test server. */
class SparkProfileTest {
    static SparkProfile fixture() throws Exception {
        return SparkProfile.read(fixtureFile());
    }

    static File fixtureFile() throws URISyntaxException {
        return new File(SparkProfileTest.class.getResource("/fixtures/small.sparkprofile").toURI());
    }

    @Test
    void readsThreadsTicksAndTimes() throws Exception {
        final SparkProfile p = fixture();
        assertFalse(p.threads.isEmpty(), "no threads parsed");
        assertTrue(p.ticks > 0, "no ticks recorded");
        assertTrue(p.durationMillis() > 1000, "implausibly short profile: " + p.durationMillis() + "ms");
        assertTrue(p.threads.stream().anyMatch(t -> t.name.equals("Server thread")),
                "main thread missing; got " + p.threads.stream().map(t -> t.name).toList());
    }

    @Test
    void treeIndicesStayInsideEachThread() throws Exception {
        // Idle threads (the JVM's Attach Listener, say) can have no samples at all; that is fine.
        for (final SparkProfile.ThreadData t : fixture().threads) {
            for (final int root : t.roots) assertTrue(root >= 0 && root < t.nodes.size(), t.name + " root " + root);

            for (final SparkProfile.Node n : t.nodes) {
                assertFalse(n.className.isEmpty() && n.methodName.isEmpty(), "blank frame in " + t.name);
                assertTrue(n.time >= 0, "negative time in " + t.name);

                for (final int child : n.children)
                    assertTrue(child >= 0 && child < t.nodes.size(), t.name + " child " + child);
            }
        }
    }

    @Test
    void emptyInputGivesAnEmptyProfileNotACrash() {
        final SparkProfile p = SparkProfile.parse(new byte[0]);
        assertTrue(p.threads.isEmpty());
        assertEquals(0, p.durationMillis());
    }

    @Test
    void analysisSharesAreConsistent() throws Exception {
        final ProfileAnalysis.Result r = ProfileAnalysis.analyse(fixture(), Map.of());
        assertTrue(r.ticks() > 0);
        assertTrue(r.mainBusyMillis() >= 0);
        double categoryTotal = 0;

        for (final ProfileAnalysis.Share s : r.categories()) {
            assertTrue(s.percent() >= 0 && s.percent() <= 100.0001, s.name() + " " + s.percent());
            assertNotNull(s.advice(), s.name() + " has no advice");
            categoryTotal += s.percent();
        }
        assertTrue(categoryTotal <= 100.5, "categories overlap: " + categoryTotal + "%");

        for (int i = 1; i < r.categories().size(); i++)
            assertTrue(r.categories().get(i - 1).millis() >= r.categories().get(i).millis(), "categories not sorted");

        for (final ProfileAnalysis.ThreadLoad t : r.otherThreads())
            assertTrue(t.busyPercent() >= 0 && t.busyPercent() <= 100.0001, t.name() + " " + t.busyPercent());
    }

    @Test
    void foliaRegionThreadsCountAsTheTick() throws Exception {
        // Spark on Folia: "Server thread" is nearly idle; the tick runs on region threads.
        final SparkProfile folia = SparkProfile.read(new File(
                SparkProfileTest.class.getResource("/fixtures/folia.sparkprofile").toURI()));
        final ProfileAnalysis.Result r = ProfileAnalysis.analyse(folia, Map.of());
        assertTrue(r.mainBusyMillis() > 0, "region threads were not counted as the tick");
        assertTrue(r.otherThreads().stream().noneMatch(t -> t.name().contains("Region Scheduler Thread")),
                "region threads listed as 'other threads'");
    }

    @Test
    void foliaPartsAddUpToTheTick() throws Exception {
        // The headline tick and the category figures must share one divisor, or a category can
        // read larger than the whole tick; and two regions mean twice the ticks in the same time.
        final SparkProfile folia = SparkProfile.read(new File(
                SparkProfileTest.class.getResource("/fixtures/folia.sparkprofile").toURI()));
        final ProfileAnalysis.Result one = ProfileAnalysis.analyse(folia, Map.of(), 1);
        final double parts = one.categories().stream().mapToDouble(ProfileAnalysis.Share::perTick).sum();
        assertEquals(one.mainBusyPerTick(), parts, one.mainBusyPerTick() * 0.01);

        for (final ProfileAnalysis.Share c : one.categories())
            assertTrue(c.perTick() <= one.mainBusyPerTick() + 1e-9, c.name() + " exceeds the whole tick");

        final ProfileAnalysis.Result two = ProfileAnalysis.analyse(folia, Map.of(), 2);
        assertEquals(one.mainBusyPerTick() / 2, two.mainBusyPerTick(), one.mainBusyPerTick() * 0.01);
    }

    @Test
    void tickThreadNames() {
        assertTrue(ProfileAnalysis.isTickThread("Server thread"));
        assertTrue(ProfileAnalysis.isTickThread("Folia Region Scheduler Thread (x2)"));
        assertFalse(ProfileAnalysis.isTickThread("Folia Async Scheduler Thread (x4)"));
        assertEquals(2, ProfileAnalysis.pooledCount("Folia Region Scheduler Thread (x2)"));
        assertEquals(1, ProfileAnalysis.pooledCount("Server thread"));
    }

    @Test
    void attributesTimeToTheEventListenerThatRanIt() {
        // callEvent -> plugin's onMove listener -> work. The whole subtree's busy time must
        // land on that listener, under its plugin.
        final SparkProfile p = new SparkProfile();
        p.ticks = 20;
        final SparkProfile.ThreadData t = new SparkProfile.ThreadData();
        t.name = "Server thread";
        t.nodes.add(node("org.bukkit.plugin.RegisteredListener", "callEvent", 10, 1)); // 0 dispatch
        t.nodes.add(node("com.example.MyListener", "onMove", 10, 2)); // 1 listener
        t.nodes.add(node("com.example.Physics", "recalculate", 8)); // 2 work leaf
        t.roots = new int[]{0};
        p.threads.add(t);

        final ProfileAnalysis.Result r = ProfileAnalysis.analyse(p, Map.of("com.example", "MyPlugin"));
        final var listeners = r.listenersByPlugin().get("MyPlugin");
        assertNotNull(listeners, "plugin's listeners missing");
        assertEquals("MyListener.onMove", listeners.get(0).frame());
        assertEquals(10.0, listeners.get(0).millis(), 1e-9, "listener should own the whole subtree");
    }

    private static SparkProfile.Node node(String cls, String method, double time, int... children) {
        final SparkProfile.Node n = new SparkProfile.Node();
        n.className = cls;
        n.methodName = method;
        n.time = time;
        n.children = children;

        return n;
    }

    @Test
    void attributesAPackageToItsPlugin() throws Exception {
        // Everything under org.bukkit is claimed by a made-up plugin: any hot frame there
        // must then be credited to it.
        final ProfileAnalysis.Result r = ProfileAnalysis.analyse(fixture(), Map.of("org.bukkit.", "Imaginary"));

        for (final ProfileAnalysis.HotMethod m : r.hotMethods())
            if (m.frame().startsWith("org.bukkit.")) assertEquals("Imaginary", m.plugin(), m.frame());
    }
}

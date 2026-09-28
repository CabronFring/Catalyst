// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a spark call tree and says where the tick went, in words. Spark reports are hard to use
 * because time appears at every level of a deep tree; this throws away idle time, charges each busy
 * sample to the most specific known activity on its stack and to the outermost plugin that called
 * in, and ranks the result.
 */
public final class ProfileAnalysis {
    public record Share(String name, double millis, double perTick, double percent, String advice) {}

    public record HotMethod(String frame, String plugin, double millis, double percent) {}

    public record ThreadLoad(String name, double busyPercent) {}

    public record Result(
            double durationSeconds, int ticks,
            double mainBusyMillis, double mainBusyPerTick,
            List<Share> categories, List<Share> plugins,
            List<HotMethod> hotMethods, List<ThreadLoad> otherThreads,

            /** Each plugin's event listeners that showed up in the tick, worst first. */
            Map<String, List<HotMethod>> listenersByPlugin,

            /** spark counted no ticks (as on Folia), so they were estimated at 20 a second. */
            boolean ticksEstimated,

            /** What "Other server work" was, by the deepest server method under it, worst first. */
            List<HotMethod> otherParts) {}

    /** What an admin can actually do about each kind of load. */
    private record Category(String name, String advice, String... markers) {}

    // Checked from the leaf upward, so the most specific match on the stack wins:
    // pathfinding beats the entity tick that called it.
    private static final List<Category> CATEGORIES = List.of(
        new Category("Mob pathfinding",
            "Mobs recalculating routes. Lower entity-activation-range, cap mobs per chunk, "
          + "or disable update-pathfinding-on-block-update.",
            "PathFinder", "pathfinder.", "PathNavigation", "findPath"),
        new Category("Mob AI",
            "Mob brains and goals. Usually too many mobs in loaded chunks - run /catalyst perf lag "
          + "and look for crowded chunks.",
            "GoalSelector", ".Brain.", "Sensor", "aiStep", "customServerAiStep"),
        new Category("Hoppers",
            "Hopper transfers. Enable hopper ignore-occluding-blocks, or use fewer hopper chains.",
            "HopperBlockEntity"),
        new Category("Redstone",
            "Redstone updates. Look for clocks with /catalyst perf lag, or switch the "
          + "redstone implementation to ALTERNATE_CURRENT.",
            "RedStoneWireBlock", "RedstoneWireTurbo", "alternate.current", "DiodeBlock",
            "PistonBaseBlock", "ObserverBlock"),
        new Category("Block entities",
            "Furnaces, chests, spawners and other ticking blocks.",
            "tickBlockEntities", "TickingBlockEntity", "BlockEntityTicker"),
        new Category("Liquids and block ticks",
            "Flowing water/lava and scheduled block updates. Often an unfinished farm or a flood.",
            "FlowingFluid", "LevelTicks", "tickBlock", "randomTick", "tickFluid"),
        new Category("Mob spawning",
            "The natural spawner. Lower spawn limits or increase ticks-per monster-spawns.",
            "NaturalSpawner", "spawnForChunk", "spawnCategoryForChunk"),

        // Runs under ChunkMap.tick, so without its own markers it was counted as chunk loading.
        new Category("Entity tracking",
            "Telling each player what moves around them; grows with players and entities seen "
          + "together. Lower entity-tracking-range, or spread players and mobs out.",
            "TrackedEntity", "ServerEntity"),
        new Category("Sending packets",
            "Writing packets out to players' connections. Grows with players and with what each "
          + "is sent - chunks, entities, and plugins sending extras.",
            "Connection.flush", "Connection.send", "Connection.doSendPacket", "flushChannel"),
        new Category("Chunk loading and generation",
            "Loading or generating terrain. Pre-generate the world, or lower view-distance.",
            "ChunkMap", "ChunkGenerator", "NoiseBasedChunkGenerator", "ChunkHolderManager",
            "ChunkTaskScheduler", "chunk_system", "ChunkStatus", "ServerChunkCache"),
        new Category("Lighting",
            "Light recalculation, usually from chunk generation or large block changes.",
            "LightEngine", "starlight", "StarLight"),
        new Category("World saving",
            "Writing chunks to disk. Heavy spikes here usually mean slow storage.",
            "saveAllChunks", "RegionFile", "ChunkStorage", "autoSave", "saveAll"),
        new Category("Player packets",
            "Handling what players send. High values with few players can mean an exploit "
          + "or a plugin doing work inside packet handling.",
            "ServerGamePacketListenerImpl", "PacketUtils", "handleMovePlayer"),
        new Category("Entity ticking",
            "Entities in general - items, arrows, minecarts and mobs. Lower simulation-distance "
          + "or clear dropped items.",
            "tickNonPassenger", "EntityTickList", "tickEntity", ".Entity.tick", "baseTick"),

        // Only what nothing more specific claimed: packet handling and chunk callbacks run
        // from here too, but are matched further down the stack first.
        new Category("Tasks between ticks",
            "Callbacks the server runs while it waits for the next tick, such as finished chunk "
          + "loads. Mostly spare time; it only delays ticks once the server is running behind.",
            "recordTaskExecutionTimeWhileWaiting", "pollTaskInternal", "doRunTask"),
        new Category("Scheduled plugin tasks",
            "Work plugins scheduled onto the main thread. See the plugin breakdown below.",
            "CraftScheduler", "mainThreadHeartbeat")
    );

    private static final String OTHER = "Other server work";

    /**
     * The deepest server frame on a stack, e.g. "Connection.tick": a JDK or library leaf
     * (HashMap.get) says nothing about what the server was doing.
     */
    static String serverFrame(List<SparkProfile.Node> path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            final String cn = path.get(i).className;

            if (cn.startsWith("net.minecraft.") || cn.startsWith("io.papermc.") || cn.startsWith("org.bukkit.")
                    || cn.startsWith("ca.spigotmc.") || cn.startsWith("org.spigotmc."))
                return cn.substring(cn.lastIndexOf('.') + 1) + "." + path.get(i).methodName;
        }

        return path.isEmpty() ? "?" : shortFrame(path.get(path.size() - 1));
    }

    /** Leaf frames that mean the thread was waiting, not working. */
    private static final String[] IDLE_MARKERS = {
        "Unsafe.park", "LockSupport.park", "Thread.sleep", "Object.wait", "Thread.onSpinWait",
        "waitUntilNextTick", "managedBlock", "waitForTasks",
        ".poll0", ".poll(", "select0", "doSelect", "EPoll.wait", "WEPoll", "epollWait",
        "awaitWork", "SocketDispatcher.read", "FileDispatcherImpl.read", "readBytes",
        "ReferenceQueue.remove", "Reference.waitForReferencePendingList"
    };

    private ProfileAnalysis() {}

    /**
     * @param packageOwners plugin package prefixes to plugin names, used when spark's own
     *                      class attribution is missing a class
     */
    public static Result analyse(SparkProfile profile, Map<String, String> packageOwners) {
        return analyse(profile, packageOwners, 1);
    }

    /**
     * @param tickingRegions on Folia, how many regions were ticking. Each ticks on its own, so
     *                       a second holds 20 ticks per region, not 20 in all.
     */
    public static Result analyse(SparkProfile profile, Map<String, String> packageOwners, int tickingRegions) {
        final double seconds = profile.durationMillis() / 1000.0;

        // spark-folia records no ticks at all; dividing by one "tick" would report the whole
        // profile as a single tick. Estimate them at 20 a second per region instead.
        final boolean ticksEstimated = profile.ticks <= 0;
        final int ticks = ticksEstimated
                ? (int) Math.max(1, Math.round(sampledSeconds(profile, seconds) * 20 * Math.max(1, tickingRegions)))
                : profile.ticks;

        final List<SparkProfile.ThreadData> tickThreads = new ArrayList<>();
        List<ThreadLoad> others = new ArrayList<>();

        for (final SparkProfile.ThreadData thread : profile.threads) {
            // Spark's own sampling threads are the profiler watching itself.
            if (thread.name.startsWith("spark-")) continue;

            if (isTickThread(thread.name)) {
                tickThreads.add(thread);
                continue;
            }
            final double busy = busyMillis(thread);

            if (thread.time > 0 && busy > 0)
                others.add(new ThreadLoad(thread.name, 100.0 * busy / thread.time));
        }
        others.sort((a, b) -> Double.compare(b.busyPercent(), a.busyPercent()));

        if (others.size() > 5) others = new ArrayList<>(others.subList(0, 5));

        final Map<String, Double> byCategory = new LinkedHashMap<>();
        final Map<String, Double> byPlugin = new HashMap<>();
        final Map<String, double[]> byMethod = new HashMap<>();
        final Map<String, String> methodOwner = new HashMap<>();

        // plugin -> (listener frame -> millis): time spent inside each @EventHandler method.
        final Map<String, Map<String, double[]>> byListener = new HashMap<>();
        final Map<String, double[]> byOther = new HashMap<>();
        double mainBusy = 0;

        for (final SparkProfile.ThreadData thread : tickThreads) {
            final Walker walker = new Walker(thread, profile.classSources, packageOwners,
                    byCategory, byPlugin, byMethod, methodOwner, byListener, byOther);

            for (final int root : thread.roots) walker.walk(root, new ArrayList<>());
            mainBusy += walker.busy;
        }

        // The same divisor as every category and plugin below, so the parts add up to the whole.
        // On Folia the ticks are per region: however many threads run them, each region's tick
        // has the full 50ms budget.
        final double busyPerTick = mainBusy / ticks;

        final double busyForShare = Math.max(mainBusy, 1e-9);
        final List<Share> categories = new ArrayList<>();

        for (final Map.Entry<String, Double> e : byCategory.entrySet()) {
            final String advice = CATEGORIES.stream().filter(c -> c.name.equals(e.getKey()))
                    .map(Category::advice).findFirst().orElse("");
            categories.add(share(e.getKey(), e.getValue(), ticks, busyForShare, advice));
        }
        categories.sort((a, b) -> Double.compare(b.millis(), a.millis()));

        final List<Share> plugins = new ArrayList<>();

        for (final Map.Entry<String, Double> e : byPlugin.entrySet())
            plugins.add(share(e.getKey(), e.getValue(), ticks, busyForShare, ""));
        plugins.sort((a, b) -> Double.compare(b.millis(), a.millis()));

        List<HotMethod> hot = new ArrayList<>();

        for (final Map.Entry<String, double[]> e : byMethod.entrySet())
            hot.add(new HotMethod(e.getKey(), methodOwner.get(e.getKey()),
                    e.getValue()[0], 100.0 * e.getValue()[0] / busyForShare));
        hot.sort((a, b) -> Double.compare(b.millis(), a.millis()));

        if (hot.size() > 6) hot = new ArrayList<>(hot.subList(0, 6));

        final Map<String, List<HotMethod>> listenersByPlugin = new HashMap<>();

        for (final Map.Entry<String, Map<String, double[]>> plugin : byListener.entrySet()) {
            final List<HotMethod> listeners = new ArrayList<>();

            for (final Map.Entry<String, double[]> l : plugin.getValue().entrySet())
                listeners.add(new HotMethod(l.getKey(), plugin.getKey(),
                        l.getValue()[0], 100.0 * l.getValue()[0] / busyForShare));
            listeners.sort((a, b) -> Double.compare(b.millis(), a.millis()));
            listenersByPlugin.put(plugin.getKey(), listeners);
        }

        List<HotMethod> otherParts = new ArrayList<>();

        for (final Map.Entry<String, double[]> e : byOther.entrySet())
            otherParts.add(new HotMethod(e.getKey(), null, e.getValue()[0], 100.0 * e.getValue()[0] / busyForShare));
        otherParts.sort((a, b) -> Double.compare(b.millis(), a.millis()));

        if (otherParts.size() > 5) otherParts = new ArrayList<>(otherParts.subList(0, 5));

        return new Result(seconds, ticks, mainBusy, busyPerTick,
                categories, plugins, hot, others, listenersByPlugin, ticksEstimated, otherParts);
    }

    /**
     * The threads that run the game tick: "Server thread" everywhere, plus Folia's region
     * threads, which spark reports merged as e.g. "Folia Region Scheduler Thread (x2)".
     */
    static boolean isTickThread(String name) {
        return name.equals("Server thread") || name.contains("Region Scheduler Thread");
    }

    /** How long the tick threads were sampled for, which is shorter than the whole profile. */
    private static double sampledSeconds(SparkProfile profile, double fallback) {
        for (final SparkProfile.ThreadData thread : profile.threads)
            if (isTickThread(thread.name) && thread.time > 0)
                return thread.time / pooledCount(thread.name) / 1000.0;

        return fallback;
    }

    /** How many threads spark merged into one entry: "(x2)" is two, no suffix is one. */
    static int pooledCount(String name) {
        final Matcher m = Pattern.compile("\\(x(\\d+)\\)$").matcher(name);

        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static Share share(String name, double millis, int ticks, double busy, String advice) {
        return new Share(name, millis, millis / ticks, 100.0 * millis / busy, advice);
    }

    /** Busy time for a thread: everything except samples whose leaf is waiting. */
    private static double busyMillis(SparkProfile.ThreadData thread) {
        final double[] busy = {0};

        for (final int root : thread.roots) sumBusy(thread, root, busy);

        return busy[0];
    }

    private static void sumBusy(SparkProfile.ThreadData thread, int index, double[] busy) {
        final SparkProfile.Node node = thread.nodes.get(index);
        final double self = selfTime(thread, node);

        if (self > 0 && !isIdle(node)) busy[0] += self;

        for (final int child : node.children) sumBusy(thread, child, busy);
    }

    private static double selfTime(SparkProfile.ThreadData thread, SparkProfile.Node node) {
        double children = 0;

        for (final int child : node.children) children += thread.nodes.get(child).time;

        return Math.max(0, node.time - children);
    }

    private static boolean isIdle(SparkProfile.Node node) {
        final String frame = node.className + "." + node.methodName + "(";

        for (final String marker : IDLE_MARKERS) if (frame.contains(marker)) return true;

        return false;
    }

    /** Walks the main thread once, charging busy self time to category, plugin and method. */
    private static final class Walker {
        final SparkProfile.ThreadData thread;
        final Map<String, String> classSources;
        final Map<String, String> packageOwners;
        final Map<String, Double> byCategory, byPlugin;
        final Map<String, double[]> byMethod;
        final Map<String, String> methodOwner;
        final Map<String, Map<String, double[]>> byListener;
        final Map<String, double[]> byOther;
        double busy;

        Walker(SparkProfile.ThreadData thread, Map<String, String> classSources,
               Map<String, String> packageOwners, Map<String, Double> byCategory,
               Map<String, Double> byPlugin, Map<String, double[]> byMethod,
               Map<String, String> methodOwner, Map<String, Map<String, double[]>> byListener, Map<String, double[]> byOther) {
            this.thread = thread;
            this.classSources = classSources;
            this.packageOwners = packageOwners;
            this.byCategory = byCategory;
            this.byPlugin = byPlugin;
            this.byMethod = byMethod;
            this.methodOwner = methodOwner;
            this.byListener = byListener;
            this.byOther = byOther;
        }

        void walk(int index, List<SparkProfile.Node> path) {
            final SparkProfile.Node node = this.thread.nodes.get(index);
            path.add(node);

            final double self = selfTime(this.thread, node);

            if (self > 0 && !isIdle(node)) {
                this.busy += self;
                final String category = this.categoryOf(path);
                this.byCategory.merge(category, self, Double::sum);

                if (category.equals(OTHER)) this.byOther.computeIfAbsent(serverFrame(path), k -> new double[1])[0] += self;

                final String plugin = this.outermostPlugin(path);

                if (plugin != null) this.byPlugin.merge(plugin, self, Double::sum);

                final String frame = shortFrame(node);
                this.byMethod.computeIfAbsent(frame, k -> new double[1])[0] += self;

                if (plugin != null) this.methodOwner.putIfAbsent(frame, plugin);

                this.chargeListener(path, self);
            }

            for (final int child : node.children) this.walk(child, path);
            path.remove(path.size() - 1);
        }

        /**
         * Charges this sample's self time to the event listener it ran under, if any. A
         * listener method is the plugin frame Bukkit's event dispatch calls directly, so it
         * is the frame whose caller on the stack is a RegisteredListener or EventExecutor.
         * The outermost such frame is used, so a listener that fires further events keeps the
         * whole cost - the same rule outermostPlugin() uses for the plugin total.
         */
        private void chargeListener(List<SparkProfile.Node> path, double self) {
            for (int i = 1; i < path.size(); i++) {
                if (!isDispatchFrame(path.get(i - 1))) continue;

                // The JVM's own method-handle plumbing can sit between the executor and the
                // listener (Paper 26 shows it), so step over it to the first real frame.
                int j = i;

                while (j < path.size() && isJvmPlumbing(path.get(j))) j++;

                if (j >= path.size()) return;
                final String owner = this.ownerOf(path.get(j).className);

                if (owner == null) continue;
                this.byListener.computeIfAbsent(owner, k -> new HashMap<>())
                        .computeIfAbsent(shortFrame(path.get(j)), k -> new double[1])[0] += self;
                return;
            }
        }

        private String categoryOf(List<SparkProfile.Node> path) {
            for (int i = path.size() - 1; i >= 0; i--) {
                final String frame = path.get(i).className + "." + path.get(i).methodName;

                for (final Category c : CATEGORIES)
                    for (final String marker : c.markers)
                        if (frame.contains(marker)) return c.name;
            }
            return OTHER;
        }

        /**
         * The first plugin frame from the root is the plugin that started the work, which
         * is the one to hold responsible - its listener or task called everything below.
         */
        private String outermostPlugin(List<SparkProfile.Node> path) {
            for (final SparkProfile.Node frame : path) {
                final String owner = this.ownerOf(frame.className);

                if (owner != null) return owner;
            }
            return null;
        }

        private String ownerOf(String className) {
            final String fromSpark = this.classSources.get(className);

            if (fromSpark != null) return fromSpark;

            String best = null;
            int bestLength = 0;

            for (final Map.Entry<String, String> e : this.packageOwners.entrySet()) {
                final String prefix = e.getKey();

                if (prefix.length() > bestLength && className.startsWith(prefix + ".")) {
                    best = e.getValue();
                    bestLength = prefix.length();
                }
            }
            return best;
        }
    }

    private static String shortFrame(SparkProfile.Node node) {
        final String cls = node.className;
        final int dot = cls.lastIndexOf('.');
        final String simple = dot < 0 ? cls : cls.substring(dot + 1);

        return simple + "." + node.methodName + (node.line > 0 ? ":" + node.line : "");
    }
    /** Frames the JVM adds when a method handle or reflection calls the listener: never the listener itself. */
    private static boolean isJvmPlumbing(SparkProfile.Node node) {
        final String c = node.className;

        return c.startsWith("java.lang.invoke.") || c.startsWith("jdk.internal.") || c.startsWith("java.lang.reflect.");
    }

    /**
     * A frame that is Bukkit's event dispatch calling into a listener: the plugin frame right
     * below it is the @EventHandler method. Covers RegisteredListener and every EventExecutor
     * (the reflection MethodHandleEventExecutor, the ASM-generated ones, Paper's timing wrapper).
     */
    private static boolean isDispatchFrame(SparkProfile.Node node) {
        return node.className.contains("EventExecutor")
                || (node.className.endsWith("RegisteredListener") && node.methodName.equals("callEvent"));
    }
}

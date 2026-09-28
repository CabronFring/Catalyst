// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import gg.catalyst.Catalyst;
import gg.catalyst.bench.bots.BotStage;
import gg.catalyst.chat.ChatLinks;
import gg.catalyst.event.CatalystBenchmarkCompleteEvent;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.optimization.ScanResult;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.report.Rating;
import gg.catalyst.spark.ProfileAnalysis;
import gg.catalyst.spark.SparkSupport;
import gg.catalyst.upload.UploadResult;
import gg.catalyst.util.Branding;
import gg.catalyst.util.WorldPaths;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Piston;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Measures what common kinds of load cost this particular server, so "how much more can it take?"
 * has an answer. Each stage adds one fixed load in a throwaway world, lets it settle, reads the tick
 * time and removes it, so every figure is a clean difference against the empty baseline; every stage
 * runs several times and the median is taken, so one noisy window can't distort it. The worlds are
 * deleted afterwards, or on the next start if the server died mid-run. Roaming bots (bench bots here)
 * are the exception: they join a real world and change nothing in it.
 *
 * The player stage can't make a connected player through the API, so it measures a player's world
 * footprint - the chunks kept loaded around them; the optional real-client stage connects real bots.
 */
public final class Benchmark {
    public static final String WORLD_NAME = "catalyst_bench";
    /** A second throwaway world: the real world's seed and generator, for timing real terrain. */
    public static final String TERRAIN_NAME = "catalyst_bench_terrain";

    /** Whether a world is one of the benchmark's own, which runtime modules and censuses leave alone. */
    public static boolean isBenchWorld(String name) {
        return name.equals(WORLD_NAME) || name.equals(TERRAIN_NAME);
    }

    private static final double BUDGET_MS = 50.0;
    /** Free disk required before a run starts. */
    private static final long MIN_FREE_DISK = 1024L * 1024 * 1024;
    // Let the load settle, then measure a full window so the average belongs to this stage alone.
    private static final int SETTLE_SECONDS = 4;
    private static final int MEASURE_SECONDS = 5;
    /** Every stage happens inside these chunks, kept ticking by a plugin ticket. */
    private static final int AREA_CHUNKS = 8;

    /**
     * Each redstone clock sits in its own cell: a dust line along x, then the observer pair
     * at the east end. Cells never touch, so no clock can power its neighbour.
     */
    private static final int CLOCK_CELL_X = 12, CLOCK_CELL_Z = 3, CLOCK_DUST = 8;
    private static final int MAX_CLOCKS = (AREA_CHUNKS * 16 / CLOCK_CELL_X) * (AREA_CHUNKS * 16 / CLOCK_CELL_Z);

    /** Hopper loops are 2x2, one per 3x3 cell of the stage area. */
    private static final int HOPPER_CELL = 3;
    private static final int MAX_HOPPERS = (AREA_CHUNKS * 16 / HOPPER_CELL) * (AREA_CHUNKS * 16 / HOPPER_CELL) * 4;
    /** Dropped items lie one per 2x2 blocks of the stage area. */
    private static final int ITEM_SPACING = 2;
    private static final int MAX_ITEMS = (AREA_CHUNKS * 16 / ITEM_SPACING) * (AREA_CHUNKS * 16 / ITEM_SPACING);

    /**
     * How much load the stages add. MEDIUM is the long-standing default. More load lifts a
     * stage's cost further above tick-to-tick noise, which helps on a fast server where a
     * small load can read "too small to measure", at the price of a harder stall while it
     * runs on a slow one. Each level means a different amount per stage (see Settings).
     */
    public enum Intensity {
        LOW, MEDIUM, HARSH, EXTREME;

        /** The level a word names, ignoring case; null when it names none. */
        public static Intensity parse(String s) {
            if (s == null) return null;

            for (final Intensity i : values()) if (i.name().equalsIgnoreCase(s.trim())) return i;
            return null;
        }
    }

    /**
     * What each stage loads, from config. Each stage lists an amount per level (LOW, MEDIUM,
     * HARSH), and the level in use - benchmark.intensity, or one chosen for the run with
     * /catalyst perf bench harsh - picks which. A level left out uses the built-in amount; 0
     * skips the stage at that level, and enabled: false at every level. A plain number instead of the list is one amount for
     * every level, as configs from before the levels have it. Clamped so a typo cannot
     * freeze the server; nothing goes past what the stage area can hold.
     */
    record Settings(Intensity intensity, int repeats, int villagers, int cows, int clocks, int footprints,
                    int generatedChunks, int hoppers, int items, int pistons, int minecarts, int explosions) {
        static Settings from(FileConfiguration c, Intensity forRun) {
            final Intensity configured = Intensity.parse(c.getString("benchmark.intensity"));
            final Intensity all = forRun != null ? forRun : configured != null ? configured : Intensity.MEDIUM;
            return new Settings(all,
                    clamp(c.getInt("benchmark.repeats", 3), 1, 5),
                    stage(c, "villagers", all, 50, 100, 300, 1000, 3000),
                    stage(c, "cows", all, 100, 200, 600, 2000, 5000),
                    stage(c, "redstone-clocks", all, 25, 50, 105, 400, MAX_CLOCKS),
                    stage(c, "player-footprints", all, 2, 3, 6, 12, 16),
                    stage(c, "chunk-generation", all, 15, 30, 60, 150, 300),

                    // Whole loops of four, so every hopper has one feeding it and one to feed.
                    stage(c, "hoppers", all, 500, 1000, 1764, 6000, MAX_HOPPERS) / 4 * 4,
                    stage(c, "dropped-items", all, 250, 500, 1024, 4000, MAX_ITEMS),
                    stage(c, "pistons", all, 25, 50, 105, 400, MAX_CLOCKS),
                    stage(c, "minecarts", all, 50, 100, 192, 800, MAX_MINECARTS),
                    stage(c, "explosions", all, 4, 10, 24, 50, 50));
        }

        private static int stage(FileConfiguration c, String key, Intensity level,
                                 int low, int medium, int harsh, int extreme, int max) {
            final String path = "benchmark.stages." + key;

            if (c.get(path) instanceof Number n) return clamp(n.intValue(), 0, max);
            final ConfigurationSection levels = c.getConfigurationSection(path);

            if (levels != null && !levels.getBoolean("enabled", true)) return 0;

            if (levels != null)
                for (final String name : levels.getKeys(false))
                    if (name.equalsIgnoreCase(level.name()) && levels.get(name) instanceof Number n)
                        return clamp(n.intValue(), 0, max);
            return clamp(switch (level) {
                case LOW -> low;
                case MEDIUM -> medium;
                case HARSH -> harsh;
                case EXTREME -> extreme;
            }, 0, max);
        }

        private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

        /** A roaming run: only the bots, in the real world, against that world as it is. */
        Settings roaming() {
            return new Settings(intensity, repeats, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Keeps only what a real-client run needs: the footprint estimate to compare against. */
        Settings botsOnly() {
            return new Settings(intensity, repeats, 0, 0, 0, Math.max(1, footprints), 0, 0, 0, 0, 0, 0);
        }
    }

    private enum Kind {
        /** Set up, settle, measure one window, tear down; again for every run. */
        MEASURED,

        /** The setup times itself (chunk generation); no tick window to wait for. */
        TIMED_DIRECTLY,

        /** Set up once, measure back-to-back windows, tear down once. For the bots: relaunching is slow. */
        CONTINUOUS
    }

    /**
     * @param settleSeconds how long the load gets to reach steady state before measuring
     * @param note          evaluated after each run, e.g. how many clocks were seen pulsing
     */
    private record Stage(String name, int units, String unitName, Kind kind, int settleSeconds,
                         Consumer<World> setup, Consumer<World> teardown, Supplier<String> note) {

        static Stage measured(String name, int units, String unitName, Consumer<World> setup, Consumer<World> teardown) {
            return new Stage(name, units, unitName, Kind.MEASURED, SETTLE_SECONDS, setup, teardown, () -> null);
        }

        long seconds(int repeats) {
            return switch (kind) {
                case MEASURED -> (long) repeats * (settleSeconds + MEASURE_SECONDS);

                // One chunk a tick, plus generating it: about a tenth of a second each.
                case TIMED_DIRECTLY -> (long) repeats * Math.max(1, units / 10);
                case CONTINUOUS -> settleSeconds + (long) repeats * MEASURE_SECONDS + BOT_COOLDOWN_SECONDS;
            };
        }
    }

    /** Bots need time to log in one by one and receive their first chunks. */
    private static final int BOT_SETTLE_SECONDS = 15;
    /** Each bot joins 0.15s after the last, then loads its first view of chunks: a big run needs longer. */
    private static int botSettleSeconds(int bots) {
        return Math.min(45, BOT_SETTLE_SECONDS + (int) Math.ceil(bots * 0.3));
    }
    /** Lets the bots finish leaving before the next stage, or before the world unloads. */
    private static final int BOT_COOLDOWN_SECONDS = 4;
    /** With spark, how long the server is profiled with the bots on it, after they are measured. */
    private static final int BOT_PROFILE_SECONDS = 20;

    private record Row(String name, int units, String unitName, double deltaMs, List<Double> runs, String note) {}

    private final Catalyst plugin;
    private final TickMeter meter;
    private final CommandSender sender;
    private final Settings settings;
    private final List<Row> rows = new ArrayList<>();
    private final List<Double> baseRuns = new ArrayList<>();
    private final List<Double> chunkGenRuns = new ArrayList<>();
    private final List<int[]> footprintChunks = new ArrayList<>();
    private final List<String> report = new ArrayList<>();

    private World world;
    private double benchBaseMs;
    // The stage running now, and an epoch bumped when one is skipped so any callback still in
    // flight for it becomes a no-op. All read and written on the global thread.
    private List<Stage> currentStages;
    private int currentIndex = -1;
    private Stage currentStage;
    private volatile int stageEpoch;
    private int footprintRadius;
    private long startedAt;
    private volatile boolean finished = false;

    // Redstone pulse check: per clock, whether its observer was seen on and off.
    private BukkitTask pulseWatch;
    private boolean[] clockSeenOn, clockSeenOff;
    private int clocksPlaced, clockY;

    // Pistons: which were seen extended and retracted, the same check as the clocks.
    private BukkitTask pistonWatch;
    private int[] pistonReturns;
    private boolean[] pistonSlimeUp;
    private int pistonTicks;
    private int pistonsPlaced, pistonY, hopperY;

    /** The real-terrain world, or null when it is not needed or could not be made (terrainSkip says why). */
    private World terrain;
    private RealWorld.Choice terrainFrom;
    private String terrainSkip;
    private BenchExit terrainExit;
    /** While the bots walk: their new chunks, also generated in the real-terrain world. */
    private Listener mirror;
    private final AtomicInteger mirrorAsked = new AtomicInteger();
    private final AtomicInteger mirrorDone = new AtomicInteger();
    /** Chunks that loaded while the bots ran, and how many of those the server had to generate. */
    private Listener chunkCounter;
    private final AtomicInteger chunksLoaded = new AtomicInteger();
    private final AtomicInteger chunksGenerated = new AtomicInteger();

    /** Null when the bot stage is off or refused. */
    private BotStage bots;
    /** spark's profile of the server with the bots on it; null without spark. */
    private ProfileAnalysis.Result botProfile;
    private boolean botsProfiled;
    private String botSkipReason;
    private String pinnedHeapNote;
    /** Why the real-client stage failed to start, if it did (Mojang lookup, a launch error). */
    private String botStageError;

    /** The real worlds, counted before the bench world exists, for the recommendations. */
    private Recommendations.Census census;

    private Benchmark(Catalyst plugin, TickMeter meter, CommandSender sender, Settings settings) {
        this.plugin = plugin;
        this.meter = meter;
        this.sender = sender;
        this.settings = settings;
    }

    /** Refuses with a reason, or returns a started benchmark. */
    public static Benchmark start(Catalyst plugin, CommandSender sender, boolean force) {
        return start(plugin, sender, force, null, null, null, false);
    }

    /**
     * @param botsOnly when set, a quick real-client run with this many bots: only the empty
     *                 world, the player footprint and the bots, whatever benchmark.bots.enabled
     *                 says. Null for the full benchmark as configured.
     * @param intensity the level for this run, over benchmark.intensity; null to use config.
     * @param roamAround for a bots-only run: bots join this spot's real world and wander around
     *                   it, measured against that world as it is; null for the bench world lanes.
     * @param spread     with roamAround: each bot roams a spot of its own scattered around it.
     */
    public static Benchmark start(Catalyst plugin, CommandSender sender, boolean force, Integer botsOnly,
                                  Intensity intensity, Location roamAround, boolean spread) {
        if (PlatformDetector.isFolia()) {
            sender.sendMessage(ChatColor.RED + "Folia cannot create worlds while running, so the benchmark is unavailable.");
            return null;
        }
        final TickMeter meter = TickMeter.create();

        if (meter == null) {
            sender.sendMessage(ChatColor.RED + "This server exposes no tick timings to measure against.");
            return null;
        }

        // A world of this name that Catalyst did not create is someone's: the benchmark would
        // load it, fill it with test load and delete it afterwards.
        for (final String name : List.of(WORLD_NAME, TERRAIN_NAME))
            for (final File candidate : WorldPaths.candidates(name)) {
                if (candidate.exists() && !new File(candidate, OWNER_MARKER).isFile()) {
                    sender.sendMessage(ChatColor.RED + "A world called " + name + " already exists (" + candidate.getPath()
                            + ") and was not made by " + Branding.name() + ", so the benchmark will not touch it."
                            + " Rename or move that world to run the benchmark.");
                    return null;
                }
            }

        // Its worlds, the chunks it generates and the bots' own chunks are written to disk as it
        // runs: tens of megabytes usually, a few hundred at most. A disk that full would fail
        // mid-run - or take the real worlds' saves down with it.
        final long free = Bukkit.getWorldContainer().getUsableSpace();

        if (free > 0 && free < MIN_FREE_DISK) {
            sender.sendMessage(ChatColor.RED + "Only " + free / (1024 * 1024) + " MB of disk space is free where the worlds are"
                    + " saved. The benchmark writes worlds and chunks as it runs, so it needs at least "
                    + MIN_FREE_DISK / (1024 * 1024) + " MB free.");
            return null;
        }

        final int online = plugin.getServer().getOnlinePlayers().size();

        // Vanilla 1.21.2+ stops ticking worlds once nobody has been online for this long, while
        // the plugin scheduler keeps running: the benchmark would carry on and time a server
        // doing nothing. Paper turns it off; Spigot and CraftBukkit keep vanilla's 60 seconds.
        final int pause = pauseWhenEmptySeconds();

        if (online == 0 && pause > 0) {
            sender.sendMessage(ChatColor.RED + "This server pauses when nobody has been online for " + pause + "s"
                    + " (pause-when-empty-seconds in server.properties). Paused worlds do not tick, so"
                    + " there would be nothing to measure.");
            sender.sendMessage(ChatColor.GRAY + "Join and stay online while it runs, or set it to -1 and restart.");
            return null;
        }
        final boolean senderOnly = online == 1 && sender instanceof Player;

        if (online > 0 && !senderOnly && !force) {
            sender.sendMessage("§c" + online + " player(s) are online. The benchmark briefly stalls the server"
                    + " while it generates chunks, and their activity would skew the numbers.");
            sender.sendMessage(ChatColor.GRAY + "Run it when the server is empty, or add " + ChatColor.WHITE + "force" + ChatColor.GRAY + " to run anyway.");
            return null;
        }

        Settings settings = Settings.from(plugin.getConfig(), intensity);

        if (botsOnly != null) settings = roamAround != null ? settings.roaming() : settings.botsOnly();
        final Benchmark bench = new Benchmark(plugin, meter, sender, settings);
        bench.roamAround = roamAround;
        bench.roamSpread = roamAround != null && spread;

        if (botsOnly != null || plugin.getConfig().getBoolean("benchmark.bots.enabled", false)) {
            switch (BotStage.plan(plugin, botsOnly)) {
                case BotStage.Ready ready -> {
                    bench.bots = ready.stage();
                    bench.pinnedHeapNote = BotStage.pinnedHeapWarning();
                }
                case BotStage.Refused refused -> {
                    // A bots-only run with no bots has nothing left to measure.
                    if (botsOnly != null) {
                        sender.sendMessage(ChatColor.RED + "The bots cannot run here: " + refused.reason());
                        return null;
                    }
                    bench.botSkipReason = refused.reason();
                }
            }
        }
        bench.begin();

        return bench;
    }

    public boolean isFinished() { return this.finished; }

    /** For a roaming run, where the bots start in the real world; null for a bench world run. */
    private Location roamAround;
    // Entities near the roaming start, before the bots join: the server tracks these to every bot,
    // so their count explains the entity-tracking figure and makes two roaming runs comparable.
    private int nearbyEntities, nearbyMobs;

    /** Non-player entities within a generous tracking range of a roaming run's centre. */
    private void countNearbyEntities(Location at) {
        int all = 0, mobs = 0;

        try {
            // On Folia this can throw off the owning region's thread; a missing count is fine.
            for (final org.bukkit.entity.Entity e : at.getWorld().getNearbyEntities(at, 128, 128, 128)) {
                if (e instanceof org.bukkit.entity.Player) continue;
                all++;

                if (e instanceof org.bukkit.entity.LivingEntity) mobs++;
            }
        } catch (Throwable ignored) {
            all = mobs = -1;
        }
        this.nearbyEntities = all;
        this.nearbyMobs = mobs;
    }
    /** A roaming run whose bots each roam a spot of their own, scattered round roamAround. */
    private boolean roamSpread;

    /** The world stages run in: the bench world, or the real one the bots roam. */
    private World stageWorld() {
        return this.world != null ? this.world : this.roamAround != null ? this.roamAround.getWorld() : null;
    }

    /** Whether the bot stage's clients are on. */
    private volatile boolean botsOn;
    /** Whether they were ever started, so a cancel knows to let them leave before closing the world. */
    private volatile boolean botsStarted;

    /**
     * Stops a run midway, with no report. Every step still scheduled sees it is finished and
     * does nothing. Bots are asked to leave the way the stage itself ends them, so their
     * player files are removed as after any run; the worlds go once they are out.
     */
    public void cancel() {
        if (this.finished) return;
        this.finished = true;
        this.closing = true;
        this.plugin.cancelQuietProfile(false);

        // Bots stopped a moment ago (at the end of their stage, or for memory) may still be
        // leaving: closing the world under them would move them into the real one.
        if (this.botsStarted && this.bots != null) {
            this.stopBots();

            // stop() sweeps up after the bots 3s on; the worlds are closed once that is done.
            this.plugin.runDelayed(() -> this.plugin.runGlobal(this::closeCancelled), 5);
            return;
        }
        this.plugin.runGlobal(this::closeCancelled);
    }

    private volatile boolean closing;

    /** Cancelled, and still removing its bots and worlds. */
    public boolean isClosing() { return this.closing && !this.released; }

    private void closeCancelled() {
        // The bots have already been stopped properly; cleanup() must not kill them over it.
        this.bots = null;
        this.cleanup();
        this.plugin.runDelayed(() -> this.plugin.runGlobal(this::reclaimMemory), 3);
        this.release();
    }

    private volatile boolean released = false;

    public boolean isReleased() { return this.released; }

    /**
     * Drops everything a finished run was holding - results, the report, the bot stage and,
     * through it, the bot process handle - so nothing from it stays in the server's memory.
     * The bot stage's own cleanup keeps its own reference until it has run.
     */
    private void release() {
        this.bots = null;
        this.world = null;
        this.census = null;
        this.rows.clear();
        this.report.clear();
        this.baseRuns.clear();
        this.chunkGenRuns.clear();
        this.footprintChunks.clear();
        this.clockSeenOn = this.clockSeenOff = null;

        // A whole call tree, held through the plugin's reference to the last run until this drops it.
        this.botProfile = null;
        this.currentStage = null;
        this.currentStages = null;
        this.released = true;
        this.plugin.benchmarkReleased(this);
    }

    /** A separator sent to chat only, not into the saved report file. */
    private void chatSep() { this.sender.sendMessage(Branding.SEP); }

    private void begin() {
        this.startedAt = System.currentTimeMillis();
        this.startTpsMonitor();
        this.chatSep();
        final List<Stage> stages = this.stages();
        // A roaming run reads its baseline from the live tick time, so that first stage costs no time.
        final long seconds = stages.stream().skip(this.roamAround != null ? 1 : 0)
                .mapToLong(s -> s.seconds(this.settings.repeats())).sum()
                + (this.bots != null && SparkSupport.isAvailable() ? BOT_PROFILE_SECONDS + 3 : 0);
        this.sender.sendMessage(String.format(ChatColor.AQUA + "Benchmark started " + ChatColor.GRAY + "("
                        + (this.roamAround != null ? this.bots.count() + (this.roamSpread ? " bots spread out around " : " bots roaming around ") + (this.sender instanceof Player ? "you" : "the spawn")
                                : this.settings.intensity().name().toLowerCase() + " load")
                        + "). About %s, " + (this.roamAround != null ? "in the world \"" + this.roamAround.getWorld().getName() + "\""
                                : "in worlds of its own")
                        + "; each stage runs %d time%s.",
                seconds < 90 ? seconds + " seconds" : Math.round(seconds / 60.0) + " minutes",
                this.settings.repeats(), this.settings.repeats() == 1 ? "" : "s"));

        if (this.settings.intensity() == Intensity.EXTREME)
            this.sender.sendMessage(ChatColor.YELLOW + "EXTREME load pushes most servers well below 20 TPS while each stage runs."
                    + " Run it when nobody is playing.");

        if (this.botSkipReason != null) this.sender.sendMessage(ChatColor.GRAY + "Skipping the real-client stage: " + this.botSkipReason);

        if (this.pinnedHeapNote != null) this.sender.sendMessage(ChatColor.YELLOW + "  Heads-up: " + this.pinnedHeapNote);

        if (this.roamAround != null)
            this.sender.sendMessage(ChatColor.GRAY + "A " + ChatColor.YELLOW + "[skip]" + ChatColor.GRAY
                    + " sits beside the real-client stage, to end it early and jump to the report.");
        else if (stages.size() > 1)
            this.sender.sendMessage(ChatColor.GRAY + "The first stage sets the baseline; a " + ChatColor.YELLOW + "[skip]"
                    + ChatColor.GRAY + " appears beside every stage after it, to move straight on.");

        this.plugin.chatLinks().send(this.sender, ChatColor.GRAY + "Changed your mind? ", ChatColor.YELLOW + "[/catalyst perf bench cancel]", "",
                "/catalyst perf bench cancel", "Stop the benchmark without a report", ChatLinks.Click.RUN);
        this.chatSep();

        this.plugin.runGlobal(() -> {
            if (this.finished) return; // cancelled before it got going

            if (this.roamAround != null) {
                // The world measured is your own, so the baseline is the server's own rolling tick
                // time, read at once rather than sat through as a stage - the bots go straight in.
                this.census = this.takeCensus();
                final double base = this.meter.averageMillis();

                if (Double.isNaN(base)) {
                    // No reading yet: measure the baseline the slow way.
                    this.runStage(stages, 0, 0, new ArrayList<>());
                    return;
                }
                this.baseRuns.add(base);
                this.benchBaseMs = base;
                this.sender.sendMessage(ChatColor.DARK_GRAY + String.format("  your world as it is: %.1fms per tick", base));
                this.awaitOwnersThenRun(stages, 1, 0);
                return;
            }

            try {
                this.census = this.takeCensus();
                this.world = this.createWorld();
            } catch (Throwable t) {
                // Without this the benchmark would count as running forever, with its world loaded.
                this.sender.sendMessage(ChatColor.RED + "Could not set up the bench world: " + t);
                this.cleanup();
                this.finished = true;
                this.release();
                return;
            }

            if (this.world == null) {
                this.sender.sendMessage(ChatColor.RED + "Could not create the bench world.");
                this.finished = true;
                this.release();
                return;
            }
            this.exit = new BenchExit(this.plugin, this.world);
            this.exit.register();

            // Real terrain is needed to time chunk generation, and for the bots to explore.
            if (this.settings.generatedChunks() > 0 || this.bots != null) {
                try {
                    this.setUpTerrain();
                } catch (Throwable t) {
                    this.terrainSkip = "the real-terrain world could not be created (" + t.getClass().getSimpleName() + ")";
                }

                if (this.terrain == null) this.sender.sendMessage(ChatColor.GRAY + "  No real-terrain world: " + this.terrainSkip + ".");
            }
            this.announceWorlds();

            // The bench world's own simulation distance: the server default, and where the footprint is measured.
            this.footprintRadius = Math.max(2, Math.min(6, this.world.getSimulationDistance()));

            for (int x = 0; x < AREA_CHUNKS; x++)
                for (int z = 0; z < AREA_CHUNKS; z++)
                    this.world.getChunkAt(x, z).addPluginChunkTicket(this.plugin);

            this.runStage(stages, 0, 0, new ArrayList<>());
        });
    }

    private List<Stage> stages() {
        final List<Stage> list = new ArrayList<>();
        list.add(Stage.measured(this.roamAround != null ? "Your world as it is" : "Empty bench world", 0, "", w -> {}, w -> {}));

        if (this.settings.villagers() > 0)
            list.add(Stage.measured("Villagers", this.settings.villagers(), "villagers",
                    w -> this.spawnGrid(w, EntityType.VILLAGER, this.settings.villagers()), this::removeEntities));

        if (this.settings.cows() > 0)
            list.add(Stage.measured("Cows", this.settings.cows(), "cows",
                    w -> this.spawnGrid(w, EntityType.COW, this.settings.cows()), this::removeEntities));

        if (this.settings.clocks() > 0)
            list.add(new Stage("Redstone clocks", this.settings.clocks(), "clocks", Kind.MEASURED, SETTLE_SECONDS,
                    w -> this.buildClocks(w, this.settings.clocks()), this::removeClocks, this::pulseNote));

        if (this.settings.pistons() > 0)
            list.add(new Stage("Pistons", this.settings.pistons(), "pistons", Kind.MEASURED, SETTLE_SECONDS,
                    this::buildPistons, this::removePistons, this::pistonNote));

        if (this.settings.hoppers() > 0)
            list.add(new Stage("Hoppers", this.settings.hoppers(), "hoppers", Kind.MEASURED, SETTLE_SECONDS,
                    this::buildHoppers, this::removeHoppers, this::hopperNote));

        if (this.settings.items() > 0)
            list.add(new Stage("Dropped items", this.settings.items(), "items", Kind.MEASURED, SETTLE_SECONDS,
                    this::dropItems, this::removeEntities, this::itemsNote));

        if (this.settings.minecarts() > 0)
            list.add(new Stage("Minecarts", this.settings.minecarts(), "minecarts", Kind.MEASURED, SETTLE_SECONDS,
                    this::buildMinecarts, this::removeMinecarts, this::minecartNote));

        if (this.settings.explosions() > 0)
            list.add(new Stage("Explosions", this.settings.explosions(), "explosions/s", Kind.MEASURED, SETTLE_SECONDS,
                    this::startExplosions, this::stopExplosions, this::explosionNote));

        if (this.settings.generatedChunks() > 0)
            list.add(new Stage("Chunk generation", this.settings.generatedChunks(), "chunks", Kind.TIMED_DIRECTLY, 0,
                    this::needTerrain, w -> {}, () -> null));

        if (this.settings.footprints() > 0)
            list.add(Stage.measured("Player footprint", this.settings.footprints(), "players",
                    this::addFootprints, this::removeFootprints));

        if (this.bots != null)
            list.add(new Stage("Real clients", this.bots.count(), "bots", Kind.CONTINUOUS, botSettleSeconds(this.bots.count()),
                    this::startBots, w -> this.stopBots(), bots::shortfall));

        return list;
    }   

    /**
     * Holds a roaming run's bots until the online-mode owner lookup answers, up to OWNER_WAIT_SECONDS.
     * The other runs leave it the earlier stages to finish under; a roaming baseline is instant, so
     * without this the bots would be due first. Proceeds the moment it answers, or when the wait ends.
     */
    private void awaitOwnersThenRun(List<Stage> stages, int index, int waited) {
        if (this.finished) return;

        if (this.bots == null || !this.bots.awaitingOwners() || waited >= OWNER_WAIT_SECONDS) {
            this.runStage(stages, index, 0, new ArrayList<>());
            return;
        }

        if (waited == 0) this.sender.sendMessage(ChatColor.DARK_GRAY + "  checking the bot names with Mojang...");
        this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> this.awaitOwnersThenRun(stages, index, waited + 1)), 1);
    }

    private static final int OWNER_WAIT_SECONDS = 12;

    private void runStage(List<Stage> stages, int index, int run, List<Double> runs) {
        if (this.finished) return; // cancelled

        if (index >= stages.size()) {
            this.plugin.runGlobal(this::finish);
            return;
        }
        final Stage stage = stages.get(index);
        this.currentStages = stages;
        this.currentIndex = index;
        this.currentStage = stage;
        final boolean continuing = stage.kind() == Kind.CONTINUOUS && run > 0;

        if (run == 0) {
            final String measuring = ChatColor.DARK_GRAY + "  measuring " + stage.name().toLowerCase() + "...  ";

            // The baseline (index 0) sets the figure every other stage is measured against, so it
            // cannot be skipped; every other stage gets a one-click [skip] beside its name.
            if (index > 0)
                this.plugin.chatLinks().send(this.sender, measuring, ChatColor.YELLOW + "[skip]", "",
                        "/catalyst perf bench skip " + index, "Skip this stage and move to the next", ChatLinks.Click.RUN);
            else
                this.sender.sendMessage(measuring);
        }

        if (!continuing) {
            try {
                stage.setup().accept(this.stageWorld());
            } catch (Throwable t) {
                this.sender.sendMessage(ChatColor.RED + "  " + stage.name() + " failed: " + t.getMessage());

                if (stage.kind() == Kind.CONTINUOUS) this.botStageError = t.getMessage();
                this.runStage(stages, index + 1, 0, new ArrayList<>());
                return;
            }
        }

        final int epoch = this.stageEpoch;

        // Chunk generation times itself as it goes; the next run starts once it is done.
        if (stage.kind() == Kind.TIMED_DIRECTLY) {
            this.generateChunks(() -> {
                if (epoch != this.stageEpoch) return; // skipped mid-generation

                if (run + 1 < this.settings.repeats()) this.runStage(stages, index, run + 1, runs);
                else this.runStage(stages, index + 1, 0, new ArrayList<>());
            }, () -> {
                if (epoch != this.stageEpoch) return;
                this.runStage(stages, index + 1, 0, new ArrayList<>());
            });
            return;
        }

        // The bots' first window waits out the join spike; watch the tick drain rather than guess a time.
        if (stage.kind() == Kind.CONTINUOUS && !continuing) {
            this.sender.sendMessage(ChatColor.DARK_GRAY + "  bots joining; letting the load settle before measuring...");
            this.settleWaited = 0;
            this.settleStreak = 0;
            this.settlePrevConn = -1;
            this.settlePrevTick = Double.NaN;
            this.settleBotsThenMeasure(stages, index, run, runs, stage, epoch);
            return;
        }

        // A continuing run follows straight on from the previous window, which is already settled.
        final long wait = continuing ? MEASURE_SECONDS : stage.settleSeconds() + MEASURE_SECONDS;
        this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> this.measureWindow(stages, index, run, runs, stage, epoch)), wait);
    }

    private void measureWindow(List<Stage> stages, int index, int run, List<Double> runs, Stage stage, int epoch) {
        if (this.finished || epoch != this.stageEpoch) return; // cancelled or skipped

        if (stage.kind() == Kind.CONTINUOUS && this.memoryStop != null) {
            this.endBotsForMemory(stages, index, run, runs, stage);
            return;
        }
        runs.add(this.meter.averageMillis());
        this.afterRun(stages, index, run, runs, stage, stage.note().get(), run + 1 >= this.settings.repeats());
    }

    /** The slowest tick seen anywhere in the run, for the lowest sustained TPS in the report. */
    private double worstTickMs;
    private String worstTickStage;
    private BukkitTask tpsMonitor;

    /**
     * Samples the tick every second for the whole run, so the lowest TPS covers every part -
     * chunk generation and settling included - not only the measured windows.
     */
    private void startTpsMonitor() {
        this.tpsMonitor = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            final double now = this.meter.averageMillis();

            if (!Double.isNaN(now) && now > this.worstTickMs) {
                this.worstTickMs = now;
                this.worstTickStage = this.currentStage == null ? null : this.currentStage.name();
            }
        }, 20L, 20L);
    }

    // Adaptive settle for the bots' first window: measure once the join spike has drained - the
    // connections stop climbing and the tick stops falling - instead of a fixed guess. The stage's
    // own settle time is the cap, so this only ever measures sooner, never later.
    private static final int BOT_SETTLE_MIN_SECONDS = 4;
    private double settlePrevTick;
    private int settlePrevConn;
    private int settleStreak;
    private int settleWaited;

    private void settleBotsThenMeasure(List<Stage> stages, int index, int run, List<Double> runs, Stage stage, int epoch) {
        if (this.finished || epoch != this.stageEpoch) return;

        final double tick = this.meter.averageMillis();
        final int conn = this.bots == null ? 0 : this.bots.connected();
        final boolean stable = this.settleWaited >= BOT_SETTLE_MIN_SECONDS && conn == this.settlePrevConn
                && !Double.isNaN(this.settlePrevTick) && Math.abs(tick - this.settlePrevTick) <= Math.max(0.75, tick * 0.08);
        this.settleStreak = stable ? this.settleStreak + 1 : 0;
        this.settlePrevTick = tick;
        this.settlePrevConn = conn;

        // Three settled seconds is the buffer on top; then the measurement window.
        if (this.settleWaited >= stage.settleSeconds() || this.settleStreak >= 3) {
            this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> this.measureWindow(stages, index, run, runs, stage, epoch)), MEASURE_SECONDS);
            return;
        }

        this.settleWaited++;
        this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> this.settleBotsThenMeasure(stages, index, run, runs, stage, epoch)), 1);
    }

    /**
     * Requested by /catalyst perf bench skip. A negative index means "whatever stage is running
     * now" (the typed command); a specific index comes from a [skip] link and is ignored unless
     * that stage is still the current one, so a stale link cannot skip the wrong stage.
     */
    public void requestSkip(int index) {
        this.plugin.runGlobal(() -> {
            if (this.finished || this.currentStage == null || this.currentIndex <= 0) return;

            if (index >= 0 && this.currentIndex != index) return;
            final Stage stage = this.currentStage;
            final List<Stage> stages = this.currentStages;
            final int at = this.currentIndex;
            this.stageEpoch++; // any callback still pending for this stage now no-ops
            this.currentStage = null;

            if (stage.kind() == Kind.CONTINUOUS) {
                this.stopBots();
                this.plugin.cancelQuietProfile(true);
                this.botsProfiled = true;
            } else if (stage.kind() == Kind.TIMED_DIRECTLY) {
                // Its per-tick generation timer would otherwise keep loading chunks into the
                // next stage's measurement, and still record a run for the skipped stage.
                this.stopChunkGen();
            }

            try {
                stage.teardown().accept(this.stageWorld());
            } catch (Throwable ignored) {
                // The world is deleted at the end regardless.
            }
            this.sender.sendMessage(ChatColor.YELLOW + "  Skipped " + stage.name().toLowerCase() + ".");
            this.runStage(stages, at + 1, 0, new ArrayList<>());
        });
    }

    private void afterRun(List<Stage> stages, int index, int run, List<Double> runs, Stage stage, String note, boolean last) {
        if (this.finished) return; // cancelled

        // Once the bots' last window is measured, and before they leave, profile the server with
        // them on it, to name what they cost on this server specifically. Afterwards, so the
        // profiler's own overhead is in none of the figures. Only with spark.
        if (stage.kind() == Kind.CONTINUOUS && last && !this.botsProfiled) {
            this.botsProfiled = true;
            final int epoch = this.stageEpoch;

            if (this.plugin.profileQuietly(BOT_PROFILE_SECONDS, result -> {
                if (this.finished || epoch != this.stageEpoch) return; // cancelled or skipped

                // A profile the bots left part-way through does not show what they cost.
                this.botProfile = this.memoryStop == null ? result : null;
                this.afterRun(stages, index, run, runs, stage,
                        this.memoryStop == null ? note : this.memoryStop + " while they were being profiled, so that profile was dropped", true);
            })) {
                this.sender.sendMessage(ChatColor.DARK_GRAY + "  profiling the server with the bots on it (" + BOT_PROFILE_SECONDS + "s)...");
                return;
            }
        }

        if (stage.kind() == Kind.MEASURED || last) {
            try {
                stage.teardown().accept(this.stageWorld());
            } catch (Throwable ignored) {
                // The world is deleted at the end regardless.
            }
        }

        if (!last) {
            this.runStage(stages, index, run + 1, runs);
            return;
        }

        if (index == 0) {
            this.baseRuns.addAll(runs);
            this.benchBaseMs = median(runs);
        } else {
            final List<Double> deltas = runs.stream().map(ms -> ms - this.benchBaseMs).toList();
            this.rows.add(new Row(stage.name(), stage.units(), stage.unitName(), median(deltas), deltas, note));
        }

        if (stage.kind() == Kind.CONTINUOUS) {
            final int epoch = this.stageEpoch;
            this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> {
                if (this.finished || epoch != this.stageEpoch) return; // cancelled or skipped
                this.runStage(stages, index + 1, 0, new ArrayList<>());
            }), BOT_COOLDOWN_SECONDS);
        } else {
            this.runStage(stages, index + 1, 0, new ArrayList<>());
        }
    }

    private void startBots(World w) {
        this.botsOn = true;
        this.botsStarted = true;
        this.memoryStop = null;
        this.startMemoryWatch();
        this.startChunkCount();

        if (this.roamAround != null) {
            this.countNearbyEntities(this.roamAround);

            try {
                this.bots.startRoaming(this.roamAround, this.roamSpread ? BotStage.spreadRadius(this.plugin) : 0);
            } catch (Exception e) {
                throw new IllegalStateException("could not launch the bots: " + e.getMessage(), e);
            }
            return;
        }
        this.startMirroring();

        try {
            this.bots.start(w);
        } catch (Exception e) {
            this.stopMirroring();
            throw new IllegalStateException("could not launch the bots: " + e.getMessage(), e);
        }

        // A little behind the first bot's spawn, looking along its lane.
        final Location first = this.bots.spawnFor(this.bots.firstName());
        this.offerTeleport(ChatColor.DARK_GRAY + "  ", ChatColor.AQUA + "[watch the bots]", w, first.getX() - 6, first.getY() + 2, first.getZ(), -90f,
                "Where the first bot spawns. Each bot walks east (+x) along its own lane, the next ones further"
                        + " south. Watching adds your own load to the figure, so stay still.");
    }

    /** Checks the heap while the bots run; null otherwise. */
    private BukkitTask memoryWatch;
    /** Why the bots were stopped early for lack of memory; null while they were not. */
    private volatile String memoryStop;

    /**
     * Every bot keeps its chunks loaded, and exploring generates more - on a server with
     * little memory, enough to run the heap out and crash it. So while they run, the heap
     * is checked every second, and past benchmark.bots.memory-limit they are stopped at once.
     */
    private void startMemoryWatch() {
        final double limit = Math.max(10, Math.min(98, this.plugin.getConfig().getInt("benchmark.bots.memory-limit", 85))) / 100.0;

        // Over the limit on two checks running, not one: a live reading counts garbage too,
        // and with G1 a busy heap sits past 90% for a moment before each young collection.
        // Memory that is really full is still full a second later.
        final int[] strikes = {0};
        this.memoryWatch = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (!this.botsOn) return;
            final double full = heapFullness();

            // The whole container too: the bot process shares its limit, and going over it gets
            // the server killed however much of its own heap is free.
            final double container = BotStage.containerFullness().orElse(0);
            final String what;

            if (container >= 0.95) what = String.format(Locale.ROOT, "the server's container was %.0f%% full", container * 100);
            else if (full >= limit) what = String.format(Locale.ROOT, "the server's memory was %.0f%% full", full * 100);
            else what = null;

            if (what == null) strikes[0] = 0;

            if (what == null || ++strikes[0] < 2) return;
            this.memoryStop = "stopped early: " + what;
            this.sender.sendMessage(ChatColor.YELLOW + "  Stopping the bots early: " + what.replace(" was ", " is ")
                    + ", and more exploring could run it out and crash the server.");
            this.stopBots();

            // Past their windows and into the profile of them: that profile no longer has them in it.
            this.plugin.cancelQuietProfile(true);
        }, 20L, 20L);
    }

    private void stopMemoryWatch() {
        if (this.memoryWatch != null) this.memoryWatch.cancel();
        this.memoryWatch = null;
    }

    /**
     * How full the heap is, 0 to 1: the fullest pool as its last garbage collection left it,
     * which is what stays in use. That reading only moves when the pool is collected, which
     * for the old generation can be rare until it is nearly full - so a pool or heap that is
     * over 90% taken right now counts too, garbage and all.
     */
    static double heapFullness() {
        double full = 0;

        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;

            // Pools with no fixed ceiling (young ones) say nothing about running out.
            final MemoryUsage afterGc = pool.getCollectionUsage();

            if (afterGc != null && afterGc.getMax() > 0) full = Math.max(full, (double) afterGc.getUsed() / afterGc.getMax());
            final MemoryUsage now = pool.getUsage();

            if (now != null && now.getMax() > 0 && (double) now.getUsed() / now.getMax() > 0.9)
                full = Math.max(full, (double) now.getUsed() / now.getMax());
        }
        final Runtime rt = Runtime.getRuntime();
        final double now = (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();

        return now > 0.9 ? Math.max(full, now) : full;
    }

    /**
     * Hands the run's memory back to the OS. G1 only uncommits the freed heap on a full collection,
     * so without this the process holds its benchmark peak until a restart, and the container check
     * refuses the next run for room that is really free.
     */
    private void reclaimMemory() {
        final MemoryUsage before = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        // -Xms == -Xmx pins the heap at full size from boot (common on panels), so no GC shrinks it.
        final boolean pinned = before.getInit() > 0 && before.getInit() >= before.getMax();

        forceGc();

        final MemoryUsage after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long handedBack = (before.getCommitted() - after.getCommitted()) / 1_048_576;
        final long collected = (before.getUsed() - after.getUsed()) / 1_048_576;

        if (handedBack >= 16)
            this.sender.sendMessage(String.format(ChatColor.DARK_GRAY + "  Memory: handed %,dMB back to the OS (heap now %,dMB of %,dMB reserved).",
                    handedBack, after.getCommitted() / 1_048_576, after.getMax() / 1_048_576));
        else if (pinned && collected >= 16)
            this.sender.sendMessage(String.format(ChatColor.DARK_GRAY + "  Memory: freed %,dMB inside the heap, but the server reserves its whole -Xmx from"
                    + " boot (-Xms=-Xmx), so it stays held from the OS until a restart or a lower -Xmx.", collected));
        else if (collected >= 64)
            this.sender.sendMessage(String.format(ChatColor.DARK_GRAY + "  Memory: freed %,dMB of garbage; a restart returns the rest to the OS.", collected));
    }

    /**
     * A full GC even under -XX:+DisableExplicitGC (a common Aikar flag), which no-ops System.gc():
     * the GC.run diagnostic command is not gated by that flag. Falls back to System.gc() otherwise.
     */
    private static void forceGc() {
        try {
            ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcRun", new Object[]{null}, new String[]{String[].class.getName()});
        } catch (Exception noDiagnosticCommand) {
            System.gc();
        }
    }

    /** Takes the bots off, once: after the stage, on a cancel, or when memory runs short. */
    private void stopBots() {
        this.stopMemoryWatch();
        this.stopChunkCount();

        if (!this.botsOn || this.bots == null) return;
        this.botsOn = false;
        this.stopMirroring();
        this.bots.stop();
    }

    /**
     * Counts the chunks that load in the stage world while the bots run, and how many of those the
     * server had to generate rather than read from disk (ChunkLoadEvent.isNewChunk). That split is
     * what separates "the bots explored new land" from "the bots loaded terrain that already existed".
     */
    private void startChunkCount() {
        final World stage = this.stageWorld();

        if (stage == null) return;
        this.chunkCounter = new Listener() {
            @org.bukkit.event.EventHandler
            public void onChunkLoad(ChunkLoadEvent event) {
                if (!event.getWorld().equals(stage)) return;
                chunksLoaded.incrementAndGet();

                if (event.isNewChunk()) chunksGenerated.incrementAndGet();
            }
        };
        Bukkit.getPluginManager().registerEvents(this.chunkCounter, this.plugin);
    }

    private void stopChunkCount() {
        if (this.chunkCounter != null) HandlerList.unregisterAll(this.chunkCounter);
        this.chunkCounter = null;
    }

    /**
     * The bots were stopped for memory before the stage finished: the windows measured with
     * them all on stand, marked as cut short, and the half-measured one is dropped.
     */
    private void endBotsForMemory(List<Stage> stages, int index, int run, List<Double> runs, Stage stage) {
        this.botsProfiled = true; // no profile of a server that is short of memory

        if (!runs.isEmpty()) {
            this.afterRun(stages, index, run, runs, stage, this.memoryStop + ", so fewer windows were measured", true);
            return;
        }
        this.sender.sendMessage(ChatColor.YELLOW + "  Real clients: " + this.memoryStop + " before a window was measured, so there is no figure.");
        this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> this.runStage(stages, index + 1, 0, new ArrayList<>())),
                BOT_COOLDOWN_SECONDS);
    }

    /**
     * Tells the sender which worlds were made and what each is for, with teleports to where
     * the load is built and into the real-terrain world.
     */
    private void announceWorlds() {
        this.sender.sendMessage(ChatColor.GRAY + "  Created " + ChatColor.WHITE + WORLD_NAME + ChatColor.GRAY + " (flat, where the load is built)"
                + (this.terrain != null ? " and " + ChatColor.WHITE + TERRAIN_NAME + ChatColor.GRAY + " (" + this.terrainFrom.name()
                + "'s terrain, where chunk generation is timed)" : "") + ". Both are deleted at the end.");

        if (this.terrain != null) this.offerTerrainTeleport();
        final boolean builds = this.settings.villagers() + this.settings.cows() + this.settings.clocks() + this.settings.pistons()
                + this.settings.hoppers() + this.settings.items() + this.settings.minecarts() + this.settings.explosions() > 0;

        if (!builds) return;

        // Just outside the corner of the stage area, looking across it.
        final int ground = this.world.getHighestBlockYAt(0, 0);
        this.offerTeleport(ChatColor.DARK_GRAY + "  ", ChatColor.AQUA + "[watch the test area]", this.world, -3.5, ground + 4, -3.5, -45f,
                "Where the villagers, cows, redstone clocks, pistons, hoppers, dropped items, minecarts and explosions appear, one kind"
                        + " at a time. Stay still while it runs: walking loads chunks in some stages and not"
                        + " others, which skews the figures. You go back where you came from at the end.");
    }

    /**
     * Into the real-terrain world, standing on its surface: spreadplayers finds solid ground,
     * where a fixed height could land in a cave or a lake. 100 chunks east of where chunk
     * generation is timed - further than any view distance reaches - so a visit cannot
     * generate those chunks first and make the figure read low.
     */
    private void offerTerrainTeleport() {
        final int x = (-2000 + 100) * 16 + 8, z = -2000 * 16 + 8; // the timed squares start at chunk -2000, -2000
        this.offerCommandIn(ChatColor.DARK_GRAY + "  ", ChatColor.AQUA + "[visit the terrain world]", this.terrain,
                String.format(Locale.ROOT, "spreadplayers %d %d 0 1 false @s", x, z),
                "A new world grown from your world's seed, for timing chunk generation - none of your builds"
                        + " are in it. You land well away from the chunks"
                        + " being timed, but your own view still makes the server generate new chunks, which"
                        + " skews whichever stage is running - stay still, or visit between stages. You go back"
                        + " where you came from at the end.");
    }

    /** A clickable teleport for a player sender; a console cannot be teleported, so it gets none. */
    private void offerTeleport(String before, String label, World w, double x, double y, double z, float yaw, String hover) {
        this.offerCommandIn(before, label, w, String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f %.0f 20", x, y, z, yaw), hover);
    }

    /** A clickable command run in world {@code w}, for the player who started the benchmark. */
    private void offerCommandIn(String before, String label, World w, String command, String hover) {
        if (!(this.sender instanceof Player)) return;
        final String key;

        try {
            key = w.getKey().toString();
        } catch (LinkageError olderApi) {
            return; // No world key to aim /execute at before 1.16.5.
        }
        this.plugin.chatLinks().send(this.sender, before, label, "", "/execute in " + key + " run " + command, hover, ChatLinks.Click.RUN);
    }

    /**
     * The bots walk the flat world, where nothing can trip them up, but a flat chunk costs a
     * fraction of a real one to generate. So each new chunk they cause is also generated at
     * the same spot in the real-terrain world, and the stage carries what exploring real
     * terrain costs. Paper only: it can generate a chunk the way a player's view does, off the
     * server thread; Spigot's only way blocks the server for the whole generation, which no
     * real player causes, so there the bots stay on flat terrain and the report says so.
     */
    private void startMirroring() {
        if (this.terrain == null || !PaperWorlds.available()) return;
        final World flat = this.world, copy = this.terrain;
        final int from = BotStage.LANES_FROM_CHUNK - 64;
        this.mirror = new Listener() {
            @org.bukkit.event.EventHandler
            public void onChunkLoad(ChunkLoadEvent event) {
                if (!event.isNewChunk() || !event.getWorld().equals(flat)) return;
                final Chunk c = event.getChunk();

                // Only the bots' lanes; nothing else is generating in the bench world now.
                if (c.getX() < from || c.getZ() < from) return;
                mirrorAsked.incrementAndGet();
                PaperWorlds.generate(copy, c.getX(), c.getZ(), done -> mirrorDone.incrementAndGet());
            }
        };
        Bukkit.getPluginManager().registerEvents(this.mirror, this.plugin);
    }

    private void stopMirroring() {
        if (this.mirror != null) HandlerList.unregisterAll(this.mirror);
        this.mirror = null;
    }

    static double median(List<Double> values) {
        final double[] sorted = values.stream().mapToDouble(Double::doubleValue).filter(d -> !Double.isNaN(d)).sorted().toArray();

        if (sorted.length == 0) return Double.NaN;
        final int mid = sorted.length / 2;

        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /**
     * A warning when a stage's runs disagree by more than 40% of its median: that figure is
     * rougher than it looks. Null when they agree, or the stage is too small for it to matter.
     */
    static String spreadNote(List<Double> runs, double median) {
        final double[] v = runs.stream().mapToDouble(Double::doubleValue).filter(d -> !Double.isNaN(d)).toArray();

        if (v.length < 2 || median < 1) return null;
        double lo = v[0], hi = v[0];

        for (final double d : v) { lo = Math.min(lo, d); hi = Math.max(hi, d); }

        if (hi - lo <= 0.4 * median) return null;
        final boolean falling = v[0] == hi && v[v.length - 1] == lo;

        return String.format(Locale.ROOT, "runs ranged %.1f-%.1fms, so this is rough%s", lo, hi,
                falling ? " - highest first and lowest last, so the load was still settling and a later figure would be lower"
                        : " - the load was not steady while it was measured");
    }

    // ── stages ────────────────────────────────────────────────────────────

    private void spawnGrid(World w, EntityType type, int count) {
        final int side = (int) Math.ceil(Math.sqrt(count));

        // The spread LOW to HARSH always had; only a crowd too big for it gets more of the area.
        final int span = Math.min(AREA_CHUNKS * 16 - 4, Math.max(60, (int) (Math.sqrt(count) * 2.4)));
        int spawned = 0;

        for (int i = 0; i < side && spawned < count; i++) {
            for (int j = 0; j < side && spawned < count; j++) {
                final int x = 2 + i * span / side, z = 2 + j * span / side;
                w.spawnEntity(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5), type);
                spawned++;
            }
        }
    }

    private void removeEntities(World w) {
        for (final Entity e : w.getEntities()) if (!(e instanceof Player)) e.remove();
    }

    /**
     * Two observers facing each other retrigger one another forever - the most common
     * clock. On its own a pair is only a couple of block updates a pulse, which is far
     * cheaper than what people build, so the rear observer also drives a line of dust,
     * where most of a real clock's cost is.
     */
    private void buildClocks(World w, int count) {
        final int y = this.clockY = w.getHighestBlockYAt(0, 0) + 1;
        final int perRow = AREA_CHUNKS * 16 / CLOCK_CELL_X;
        this.clocksPlaced = count;
        this.clockSeenOn = new boolean[count];
        this.clockSeenOff = new boolean[count];

        for (int i = 0; i < count; i++) {
            final int x0 = (i % perRow) * CLOCK_CELL_X, z = 1 + (i / perRow) * CLOCK_CELL_Z;

            for (int d = 1; d <= CLOCK_DUST; d++)
                w.getBlockAt(x0 + d, y, z).setType(Material.REDSTONE_WIRE, true);

            // The rear observer watches east and outputs west, into the dust.
            final Block rear = w.getBlockAt(x0 + CLOCK_DUST + 1, y, z);
            final Block front = w.getBlockAt(x0 + CLOCK_DUST + 2, y, z);
            this.place(rear, BlockFace.EAST);

            // Placing the second observer is the block change the first one sees,
            // which starts the loop.
            this.place(front, BlockFace.WEST);
        }

        // Sample every tick through settle and measure, so a clock that never started (or
        // stalled) is caught rather than silently measured as free.
        final int perRowFinal = perRow;
        this.pulseWatch = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            for (int i = 0; i < this.clocksPlaced; i++) {
                final int x0 = (i % perRowFinal) * CLOCK_CELL_X, z = 1 + (i / perRowFinal) * CLOCK_CELL_Z;

                if (w.getBlockAt(x0 + CLOCK_DUST + 1, y, z).getBlockData() instanceof Powerable p) {
                    if (p.isPowered()) this.clockSeenOn[i] = true;
                    else this.clockSeenOff[i] = true;
                }
            }
        }, 1L, 1L);
    }

    private void place(Block block, BlockFace facing) {
        final var data = Bukkit.createBlockData(Material.OBSERVER);

        if (data instanceof Directional d) d.setFacing(facing);
        block.setBlockData(data, true);
    }

    private int clocksPulsing() {
        if (this.clockSeenOn == null) return 0;
        int n = 0;

        for (int i = 0; i < this.clocksPlaced; i++) if (this.clockSeenOn[i] && this.clockSeenOff[i]) n++;

        return n;
    }

    private String pulseNote() {
        final int pulsing = this.clocksPulsing();

        return pulsing == this.clocksPlaced ? null
                : pulsing + " of " + this.clocksPlaced + " clocks were seen pulsing - this figure undercounts";
    }

    private void removeClocks(World w) {
        if (this.pulseWatch != null) this.pulseWatch.cancel();
        this.pulseWatch = null;
        clearLayer(w, this.clockY, 1);
    }

    /**
     * Times generating new chunks of the real world's terrain - caves, ores, trees, structures -
     * in a world made from its seed and generator. The flat bench world would time almost
     * nothing: flat chunks cost a fraction of real ones.
     */
    private void needTerrain(World flat) {
        if (this.terrain == null) throw new IllegalStateException(this.terrainSkip);
    }

    /** Generates chunks while chunk generation is being timed; null otherwise. */
    private BukkitTask chunkGen;

    /**
     * One chunk a tick, timing only the generation itself. All at once, the server thread
     * would be held for as long as every chunk of every run took: on a slow host over a
     * minute, and Paper's watchdog then stops the server.
     */
    private void generateChunks(Runnable done, Runnable failed) {
        final int count = this.settings.generatedChunks();

        // A square, not a row: every chunk needs the terrain around it partly generated first,
        // and in a square neighbours share that work as they do under a real player, where a
        // row would pay it again for nearly every chunk. A fresh square each run, so nothing is
        // loaded from disk instead of generated, on the negative side, well away from the
        // bots' lanes, whose chunks are mirrored here too.
        final int side = (int) Math.ceil(Math.sqrt(count));
        final int x0 = -2000, z0 = -2000 - this.chunkGenRuns.size() * (side + 4);
        final long[] spent = {0};
        final int[] made = {0};
        this.chunkGen = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (this.finished || this.terrain == null) {
                this.stopChunkGen(); // cancelled
                return;
            }

            try {
                final int i = made[0]++;
                final long start = System.nanoTime();
                this.terrain.getChunkAt(x0 - i % side, z0 - i / side).load(true);
                spent[0] += System.nanoTime() - start;

                if (made[0] < count) return;
                this.stopChunkGen();
                this.chunkGenRuns.add(spent[0] / 1_000_000.0 / count);

                for (int j = 0; j < count; j++) this.terrain.unloadChunk(x0 - j % side, z0 - j / side, false);
            } catch (Throwable t) {
                this.stopChunkGen();
                this.sender.sendMessage(ChatColor.RED + "  Chunk generation failed: " + t.getMessage());
                failed.run();
                return;
            }
            done.run();
        }, 1L, 1L);
    }

    private void stopChunkGen() {
        if (this.chunkGen != null) this.chunkGen.cancel();
        this.chunkGen = null;
    }

    /**
     * Hoppers in closed loops of four, each passing items to the next, so every hopper moves
     * items for as long as the stage runs - the shape of a real sorting system or item line.
     */
    private void buildHoppers(World w) {
        final int y = this.hopperY = w.getHighestBlockYAt(0, 0) + 1;
        final int perRow = AREA_CHUNKS * 16 / HOPPER_CELL;

        // Round the loop: east, south, west, north, back to the start.
        final int[][] offsets = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        final BlockFace[] facing = {BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH};

        for (int loop = 0; loop < this.settings.hoppers() / 4; loop++) {
            final int x0 = (loop % perRow) * HOPPER_CELL, z0 = (loop / perRow) * HOPPER_CELL;

            for (int k = 0; k < 4; k++) {
                final Block b = w.getBlockAt(x0 + offsets[k][0], y, z0 + offsets[k][1]);
                final var data = Bukkit.createBlockData(Material.HOPPER);

                if (data instanceof Directional d) d.setFacing(facing[k]);
                b.setBlockData(data, false);

                if (b.getState() instanceof Hopper hopper)
                    hopper.getInventory().addItem(new ItemStack(HOPPER_ITEMS[k], 16));
            }
        }
    }

    /** A different item in each hopper of a loop, so items arriving from the others show they move. */
    private static final Material[] HOPPER_ITEMS = {Material.COBBLESTONE, Material.DIRT, Material.SAND, Material.GRAVEL};

    /** Loops whose first hopper holds something it did not start with: items went round. */
    private String hopperNote() {
        final World w = this.world;

        if (w == null) return null;
        int loops = this.settings.hoppers() / 4, moving = 0, perRow = AREA_CHUNKS * 16 / HOPPER_CELL;

        for (int loop = 0; loop < loops; loop++) {
            final Block first = w.getBlockAt((loop % perRow) * HOPPER_CELL, this.hopperY, (loop / perRow) * HOPPER_CELL);

            if (first.getState() instanceof Hopper h)
                for (final ItemStack s : h.getInventory().getContents())
                    if (s != null && s.getType() != HOPPER_ITEMS[0]) { moving++; break; }
        }

        return moving == loops ? null
                : moving + " of " + loops + " hopper loops were seen moving items - this figure undercounts";
    }

    private void removeHoppers(World w) {
        // Emptied first: removing a container can spill its contents, and a thousand stacks
        // left on the ground would be counted by the dropped-items stage that comes next.
        final int perRow = AREA_CHUNKS * 16 / HOPPER_CELL;

        for (int loop = 0; loop < this.settings.hoppers() / 4; loop++) {
            final int x0 = (loop % perRow) * HOPPER_CELL, z0 = (loop / perRow) * HOPPER_CELL;

            for (int dx = 0; dx <= 1; dx++)
                for (int dz = 0; dz <= 1; dz++)
                    if (w.getBlockAt(x0 + dx, this.hopperY, z0 + dz).getState() instanceof Hopper h)
                        h.getInventory().clear();
        }
        clearLayer(w, this.hopperY, 1);

        for (final Item stray : w.getEntitiesByClass(Item.class)) stray.remove();
    }

    /** Items no hopper or merge will touch, spread and mixed so none can merge with another. */
    private static final Material[] ITEM_TYPES = {
            Material.COBBLESTONE, Material.DIRT, Material.SAND, Material.GRAVEL,
            Material.OAK_LOG, Material.OAK_PLANKS, Material.STICK, Material.COAL,
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.REDSTONE, Material.BONE,
            Material.STRING, Material.ROTTEN_FLESH, Material.WHEAT_SEEDS, Material.ARROW};

    /**
     * Dropped items lying on the ground, the way they pile up at an unlit farm or a broken
     * collection system. Items of one kind lie 8 blocks apart, beyond any merge radius servers
     * use, so the count stays what was dropped; Catalyst's own item merger ignores bench worlds.
     */
    private void dropItems(World w) {
        final int y = w.getHighestBlockYAt(0, 0) + 1;
        final int perRow = AREA_CHUNKS * 16 / ITEM_SPACING;

        for (int i = 0; i < this.settings.items(); i++) {
            final int gx = i % perRow, gz = i / perRow;
            final Material type = ITEM_TYPES[(gx % 4) + 4 * (gz % 4)];
            final Item item = w.dropItem(new Location(w, gx * ITEM_SPACING + 0.5, y, gz * ITEM_SPACING + 0.5),
                    new ItemStack(type));

            // Nobody walking through - player or mob - takes them: that would change the load
            // being measured and hand out free items. 32767 is the game's "never" value.
            item.setPickupDelay(Short.MAX_VALUE);
        }
    }

    /** How many dropped items were still lying there when measured; merging would lower it. */
    private String itemsNote() {
        final World w = this.world;

        if (w == null) return null;
        final int lying = w.getEntitiesByClass(Item.class).size();

        return lying >= this.settings.items() ? null
                : "only " + lying + " of " + this.settings.items() + " items were still lying there - this figure undercounts";
    }

    /** The pistons are powered for this many ticks, then unpowered for as many: time to finish each move. */
    private static final int PISTON_HALF_PERIOD = 6;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /**
     * Sticky pistons, each lifting a slime block with four blocks stuck to it and pulling it
     * back down - the heart of slime doors, farms and contraptions: every moved block becomes
     * a moving block entity, with updates around each, far more than a lone piston firing.
     * They fire every 12 ticks from a redstone block set and cleared beside each: an observer
     * clock's pulse is too short for a sticky piston, which would drop its block instead of
     * pulling it back. The clock's own cost is the redstone stage's.
     */
    private void buildPistons(World w) {
        final int y = this.pistonY = w.getHighestBlockYAt(0, 0) + 1;
        final int perRow = AREA_CHUNKS * 16 / CLOCK_CELL_X;
        final int count = this.pistonsPlaced = this.settings.pistons();
        this.pistonReturns = new int[count];
        this.pistonSlimeUp = new boolean[count];
        this.pistonTicks = 0;

        for (int i = 0; i < count; i++) {
            final int x = (i % perRow) * CLOCK_CELL_X + CLOCK_DUST, z = 1 + (i / perRow) * CLOCK_CELL_Z;
            final var piston = Bukkit.createBlockData(Material.STICKY_PISTON);

            if (piston instanceof Directional d) d.setFacing(BlockFace.UP);
            w.getBlockAt(x, y, z).setBlockData(piston, false);
            w.getBlockAt(x, y + 1, z).setType(Material.SLIME_BLOCK, false);

            for (final BlockFace side : SIDES)
                w.getBlockAt(x + side.getModX(), y + 1, z + side.getModZ()).setType(Material.OAK_PLANKS, false);
        }

        // Runs only when the power changes, and checks each piston then, as a move has had
        // its whole half-period to finish: checking every tick would bill its own cost to
        // the pistons being measured.
        this.pistonWatch = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            final boolean power = (this.pistonTicks / PISTON_HALF_PERIOD) % 2 == 0;
            this.pistonTicks += PISTON_HALF_PERIOD;

            for (int i = 0; i < count; i++) {
                final int x = (i % perRow) * CLOCK_CELL_X + CLOCK_DUST, z = 1 + (i / perRow) * CLOCK_CELL_Z;

                // One whole push and pull: extended by the end of a powered half, then back
                // with its slime in place by the end of the next. A sticky piston that dropped
                // its block never gets it back.
                if (!power) {
                    if (w.getBlockAt(x, y, z).getBlockData() instanceof Piston p && p.isExtended()) this.pistonSlimeUp[i] = true;
                } else if (this.pistonSlimeUp[i] && w.getBlockAt(x, y + 1, z).getType() == Material.SLIME_BLOCK) {
                    this.pistonSlimeUp[i] = false;
                    this.pistonReturns[i]++;
                }

                // West of the piston, under the plank that rides on that side of the slime.
                w.getBlockAt(x - 1, y, z).setType(power ? Material.REDSTONE_BLOCK : Material.AIR, true);
            }
        }, 1L, PISTON_HALF_PERIOD);
    }

    /** Pistons that kept lifting and pulling back their slime every cycle, or near enough. */
    private String pistonNote() {
        if (this.pistonReturns == null) return null;
        final int cycles = this.pistonTicks / (PISTON_HALF_PERIOD * 2);
        int working = 0;

        for (int i = 0; i < this.pistonsPlaced; i++) if (this.pistonReturns[i] >= cycles / 2) working++;

        return working == this.pistonsPlaced ? null
                : working + " of " + this.pistonsPlaced + " pistons kept moving their slime block - this figure undercounts";
    }

    private void removePistons(World w) {
        if (this.pistonWatch != null) this.pistonWatch.cancel();
        this.pistonWatch = null;

        // The slime and its blocks reach two above the piston when extended.
        clearLayer(w, this.pistonY, 3);
    }

    /** Each minecart runs round a 4x3 loop of rail of its own, one loop per 5x4 cell. */
    private static final int CART_CELL_X = 5, CART_CELL_Z = 4;
    private static final int MAX_MINECARTS = (AREA_CHUNKS * 16 / CART_CELL_X) * (AREA_CHUNKS * 16 / CART_CELL_Z);
    private int cartY;

    /**
     * Minecarts going round loops of rail, as on a station, a ride or a farm's collection
     * line: each moves, follows the track and checks for collisions every tick. The straight
     * rails are powered, on redstone blocks, so every cart keeps going for the whole stage.
     */
    private void buildMinecarts(World w) {
        final int y = this.cartY = w.getHighestBlockYAt(0, 0) + 1;
        final int perRow = AREA_CHUNKS * 16 / CART_CELL_X;

        for (int i = 0; i < this.settings.minecarts(); i++) {
            final int x0 = (i % perRow) * CART_CELL_X, z0 = (i / perRow) * CART_CELL_Z;

            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    if (dz == 1 && dx > 0 && dx < 3) continue; // the middle of the loop
                    final boolean straight = dz != 1 && dx > 0 && dx < 3;
                    w.getBlockAt(x0 + dx, y, z0 + dz).setType(straight ? Material.REDSTONE_BLOCK : Material.STONE, false);
                    final var data = Bukkit.createBlockData(straight ? Material.POWERED_RAIL : Material.RAIL);

                    if (data instanceof Rail rail) rail.setShape(railShape(dx, dz));

                    if (data instanceof Powerable p) p.setPowered(true);
                    w.getBlockAt(x0 + dx, y + 1, z0 + dz).setBlockData(data, false);
                }
            }
            final Minecart cart = w.spawn(new Location(w, x0 + 1.5, y + 1, z0 + 0.5), RideableMinecart.class);
            cart.setVelocity(new Vector(0.4, 0, 0));
        }
    }

    /** The loop's corners turn, its ends run north-south and its long sides east-west. */
    private static Rail.Shape railShape(int dx, int dz) {
        if (dx == 0 && dz == 0) return Rail.Shape.SOUTH_EAST;

        if (dx == 3 && dz == 0) return Rail.Shape.SOUTH_WEST;

        if (dx == 0 && dz == 2) return Rail.Shape.NORTH_EAST;

        if (dx == 3 && dz == 2) return Rail.Shape.NORTH_WEST;

        return dx == 0 || dx == 3 ? Rail.Shape.NORTH_SOUTH : Rail.Shape.EAST_WEST;
    }

    /** Carts still going round when measured; one that stopped or left its track costs less. */
    private String minecartNote() {
        final World w = this.world;

        if (w == null) return null;
        final int moving = (int) w.getEntitiesByClass(Minecart.class).stream()
                .filter(m -> m.getVelocity().lengthSquared() > 0.0025).count();

        return moving >= this.settings.minecarts() ? null
                : "only " + moving + " of " + this.settings.minecarts() + " minecarts were still going round - this figure undercounts";
    }

    private void removeMinecarts(World w) {
        this.removeEntities(w);
        clearLayer(w, this.cartY, 2);
    }

    /** Explosions go off over a grid of spots 8 blocks apart, a fresh one each time until all are used. */
    private static final int BLAST_SPACING = 8;
    private static final int BLAST_SPOTS = (AREA_CHUNKS * 16 / BLAST_SPACING) * (AREA_CHUNKS * 16 / BLAST_SPACING);
    /**
     * A stride coprime with BLAST_SPOTS (256), so stepping by it visits every spot once and, more
     * importantly, spreads consecutive explosions right across the area. Without it a run's handful
     * of explosions clump in one corner, re-breaking the same blocks and reading far too cheap.
     */
    private static final int BLAST_STRIDE = 151;
    private BukkitTask blastTask;
    private Listener blastListener;
    private int blastY, blastsSet, blastsSeen;

    /** How often the blast task fires, in ticks: four times a second, a steady stream of TNT
     * rather than one burst, as a cannon or duper produces. */
    private static final int BLAST_EVERY_TICKS = 5;

    /**
     * TNT and TNT minecarts going off in a steady stream, as cannons, TNT dupers and raids set
     * them off: each explosion traces rays through the blocks around it, breaks them, and pushes
     * and damages every entity in reach. They go off over three layers of dirt (soft, so a blast
     * carves a real crater and does the block-breaking work a real explosion would) laid for every
     * run, so each run breaks the same ground. Nothing drops: a pile of items would be counted by
     * the stages after this one.
     */
    private void startExplosions(World w) {
        final int y = this.blastY = w.getHighestBlockYAt(0, 0) + 1;

        for (int x = 0; x < AREA_CHUNKS * 16; x++)
            for (int z = 0; z < AREA_CHUNKS * 16; z++)
                for (int dy = 0; dy < 3; dy++)
                    w.getBlockAt(x, y + dy, z).setType(Material.DIRT, false);
        this.blastsSet = this.blastsSeen = 0;
        this.blastListener = new Listener() {
            @EventHandler(ignoreCancelled = true)
            public void onExplode(EntityExplodeEvent event) {
                if (!w.equals(event.getEntity().getWorld())) return;
                event.setYield(0f);
                Benchmark.this.blastsSeen++;
            }
        };
        Bukkit.getPluginManager().registerEvents(this.blastListener, this.plugin);
        final int perRow = AREA_CHUNKS * 16 / BLAST_SPACING;
        final int perSecond = this.settings.explosions();
        final int[] next = {0};

        // A fractional budget carried between firings, so the per-second rate is exact however it
        // divides across the four firings a second.
        final double[] budget = {0};
        this.blastTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            budget[0] += perSecond * BLAST_EVERY_TICKS / 20.0;
            final int n = (int) budget[0];
            budget[0] -= n;

            for (int k = 0; k < n; k++) {
                final int spot = (next[0]++ * BLAST_STRIDE) % BLAST_SPOTS;
                final Location at = new Location(w, (spot % perRow) * BLAST_SPACING + 4.5, y + 3,
                        (spot / perRow) * BLAST_SPACING + 4.5);

                // Every other one a TNT minecart, the rest primed TNT.
                if (this.blastsSet++ % 2 == 1 && detonateCart(w, at)) continue;
                w.spawn(at, TNTPrimed.class).setFuseTicks(1);
            }
        }, BLAST_EVERY_TICKS, BLAST_EVERY_TICKS);
    }

    /** False where the server has no way to set a minecart off by hand (before 1.19.4). */
    private static boolean detonateCart(World w, Location at) {
        final ExplosiveMinecart cart = w.spawn(at, ExplosiveMinecart.class);

        try {
            cart.explode();
            return true;
        } catch (NoSuchMethodError olderApi) {
            cart.remove();
            return false;
        }
    }

    /** Explosions come in bursts, so the worst tick is shown beside the average. */
    private String explosionNote() {
        final String worst = String.format("worst single tick %.1fms", this.meter.worstMillis());

        // The last second's batch may still be on its fuse.
        return this.blastsSeen + this.settings.explosions() < this.blastsSet
                ? this.blastsSeen + " of " + this.blastsSet + " explosions went off - this figure undercounts; " + worst
                : "they come in bursts: " + worst;
    }

    private void stopExplosions(World w) {
        if (this.blastTask != null) this.blastTask.cancel();
        this.blastTask = null;

        if (this.blastListener != null) HandlerList.unregisterAll(this.blastListener);
        this.blastListener = null;
        this.removeEntities(w);
        clearLayer(w, this.blastY, 4);
    }

    /** Clears {@code height} layers of the stage area from {@code y} up, without block updates. */
    private static void clearLayer(World w, int y, int height) {
        for (int x = 0; x < AREA_CHUNKS * 16; x++)
            for (int z = 0; z < AREA_CHUNKS * 16; z++)
                for (int dy = 0; dy < height; dy++) {
                    final Block b = w.getBlockAt(x, y + dy, z);

                    if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                }
    }

    /** Keeps the chunks around each simulated player ticking, as a real one would. */
    private void addFootprints(World w) {
        for (int p = 0; p < this.settings.footprints(); p++) {
            final int cx = 100 + p * 60;

            for (int x = -this.footprintRadius; x <= this.footprintRadius; x++) {
                for (int z = -this.footprintRadius; z <= this.footprintRadius; z++) {
                    w.getChunkAt(cx + x, z).addPluginChunkTicket(this.plugin);
                    this.footprintChunks.add(new int[]{cx + x, z});
                }
            }
        }
    }

    private void removeFootprints(World w) {
        for (final int[] c : this.footprintChunks) w.getChunkAt(c[0], c[1]).removePluginChunkTicket(this.plugin);
        this.footprintChunks.clear();
    }

    // ── results ───────────────────────────────────────────────────────────

    /** Shown in chat and kept for the saved report. */
    private void say(String line) {
        this.sender.sendMessage(line);
        this.report.add(line);
    }

    private void finish() {
        if (this.finished) return; // cancelled
        this.cleanup();
        this.finished = true;

        // A few seconds on, once the unloaded chunks are collectable, give the memory back to the OS.
        this.plugin.runDelayed(() -> this.plugin.runGlobal(this::reclaimMemory), 3);

        // The warm empty-world median, not the one-off reading taken at the start: a benchmark
        // launched just after boot would otherwise report startup warmup as the server's load.
        final double baseline = this.benchBaseMs;
        final double headroom = Math.max(0, BUDGET_MS - baseline);
        final long took = (System.currentTimeMillis() - this.startedAt) / 1000;

        this.chatSep();
        this.say(String.format(ChatColor.AQUA + "Benchmark results " + ChatColor.GRAY + "(%ds, median of %d run%s per stage)",
                took, this.settings.repeats(), this.settings.repeats() == 1 ? "" : "s"));
        final Rating live = baseline < 25 ? Rating.GOOD : baseline < 45 ? Rating.FAIR : Rating.POOR;
        // A roaming run's baseline is your live world; a synthetic run's is an empty flat world, so
        // don't pass that off as "your server" - it is only the clean slate each stage is measured from.
        final String baseLabel = this.roamAround != null ? "Your server right now" : "Empty bench world";
        this.say(String.format(ChatColor.GRAY + "  " + baseLabel + "  %s%.2fms" + ChatColor.GRAY + "/tick  " + ChatColor.DARK_GRAY + "-> %.1fms of headroom",
                live.color, baseline, headroom));

        if (this.roamAround == null)
            this.say(ChatColor.DARK_GRAY + "  Near-empty on purpose: each stage below is the cost that load alone adds to it.");

        // Only worth saying when the load actually pulled it under 20 TPS at some point.
        if (this.worstTickMs > BUDGET_MS) {
            final double lowTps = Math.min(20.0, 1000.0 / this.worstTickMs);
            final Rating r = lowTps >= 18 ? Rating.GOOD : lowTps >= 15 ? Rating.FAIR : Rating.POOR;
            this.say(String.format(ChatColor.GRAY + "  Lowest while running  %s%.1f TPS " + ChatColor.DARK_GRAY + "(%.0fms/tick, during %s)",
                    r.color, lowTps, this.worstTickMs, this.worstTickStage == null ? "the run" : this.worstTickStage.toLowerCase()));
        }

        // The first baseline run far above the median means the server was still warming up.
        if (!this.baseRuns.isEmpty() && this.baseRuns.get(0) > baseline * 1.5 + 5)
            this.say(ChatColor.DARK_GRAY + "  (The server was still warming up as this started; the figure above is the settled reading.)");

        // No real-client figure: say so, or the smaller rows below read as the result.
        if (this.bots != null && this.row("Real clients") == null) {
            if (this.memoryStop != null)
                this.say(ChatColor.YELLOW + "  The real-client stage got no figure - " + this.memoryStop.replace("stopped early: ", "")
                        + ". Fewer bots, a smaller -Xmx, or a larger container would let it finish.");
            else
                this.say(ChatColor.YELLOW + "  The real-client stage got no figure"
                        + (this.botStageError != null ? " - " + this.botStageError : "") + ", so there is nothing here to measure the bots by.");
        }

        for (final Row row : this.rows) {
            final double perUnit = row.deltaMs() / Math.max(1, row.units());
            final boolean negligible = row.deltaMs() < 0.05;
            final Rating rating = negligible ? Rating.GOOD : perUnit * 100 < 2 ? Rating.GOOD
                    : perUnit * 100 < 10 ? Rating.FAIR : Rating.POOR;

            String extra;

            if (negligible) extra = ChatColor.DARK_GRAY + "too small to measure";
            else extra = String.format(ChatColor.DARK_GRAY + "room for ~%,d more", (long) (headroom / perUnit));

            this.say(String.format(ChatColor.GRAY + "  %-3d %-16s %s+%.2fms" + ChatColor.GRAY + "/tick  %s",
                    row.units(), row.unitName(), rating.color, Math.max(0, row.deltaMs()), extra));

            if (row.note() != null) this.say(ChatColor.YELLOW + "      " + row.note());

            // "Room for N more" is from the server as it is; say so plainly when the test itself went past it.
            if (!negligible && row.deltaMs() > headroom)
                this.say(String.format(ChatColor.YELLOW + "      %d %s went over the tick budget (below 20 TPS while they ran); about %,d fit.",
                        row.units(), row.unitName(), (long) (headroom / perUnit)));
            final String spread = spreadNote(row.runs(), row.deltaMs());

            if (spread != null) this.say(ChatColor.DARK_GRAY + "      " + spread);
        }

        final double chunkGenMs = median(this.chunkGenRuns);

        if (!Double.isNaN(chunkGenMs)) {
            final Rating gen = chunkGenMs < 20 ? Rating.GOOD : chunkGenMs < 50 ? Rating.FAIR : Rating.POOR;
            this.say(String.format(ChatColor.GRAY + "  Chunk generation     %s%.1fms" + ChatColor.GRAY + " per chunk  " + ChatColor.DARK_GRAY + "real terrain, one at a time on the server thread",
                    gen.color, chunkGenMs));
        }

        if (this.terrainFrom != null && this.terrainSkip == null)
            this.say(ChatColor.DARK_GRAY + "  Real terrain generated from " + this.terrainFrom.name() + "'s seed (" + this.terrainFrom.why()
                    + "); set benchmark.terrain-world to use another.");

        if (this.settings.footprints() > 0) {
            this.say(String.format(ChatColor.DARK_GRAY + "  Player footprint = %d ticking chunks per player (your simulation distance),",
                    (this.footprintRadius * 2 + 1) * (this.footprintRadius * 2 + 1)));
            this.say(ChatColor.DARK_GRAY + "  without mobs or packets, so real players cost more than shown.");
        }
        this.compareFootprintWithClients();
        this.reportBotFindings();

        if (this.activationLifted && this.settings.villagers() + this.settings.cows() > 0)
            this.say(ChatColor.DARK_GRAY + "  Mobs were measured fully active, as they are with a player near them (their"
                    + " activation range was lifted in the bench world only).");
        this.say(ChatColor.DARK_GRAY + "  Estimates assume load scales linearly - treat them as ceilings, not promises.");

        // The config scan reads files, so it runs off the server thread; the
        // recommendations and the saved report follow once it is back.
        this.plugin.scanAsync(scan -> {
            List<Recommendations.Item> recs = List.of();

            try {
                recs = this.recommend(scan);
            } catch (Throwable t) {
                this.say(ChatColor.GRAY + "Recommendations could not be worked out: " + t);
            }
            this.saveAndUpload(List.copyOf(this.report));

            // This scan callback is off the server thread, so hand the run to other plugins on it.
            final List<Recommendations.Item> firedRecs = recs;
            final List<String> firedReport = List.copyOf(this.report);
            this.plugin.runGlobal(() -> {
                try {
                    Bukkit.getPluginManager().callEvent(new CatalystBenchmarkCompleteEvent(
                            (int) took, baseline, firedReport, firedRecs));
                } catch (Throwable t) {
                    this.plugin.getLogger().warning("Benchmark-complete event failed: " + t);
                }
            });
            this.release();
        });
    }

    private Recommendations.Census takeCensus() {
        int villagers = 0, mobs = 0, chunks = 0, hoppers = 0, items = 0, minecarts = 0;

        for (final World w : Bukkit.getWorlds()) {
            if (isBenchWorld(w.getName())) continue;
            items += w.getEntitiesByClass(Item.class).size();

            // Every kind - hopper and chest carts included - moves and collides every tick.
            minecarts += w.getEntitiesByClass(Minecart.class).size();

            for (final LivingEntity e : w.getLivingEntities()) {
                if (e instanceof Player || e instanceof ArmorStand) continue;

                if (e instanceof AbstractVillager) villagers++;
                else mobs++;
            }

            for (final Chunk chunk : w.getLoadedChunks()) {
                chunks++;

                // The Bukkit form, not Paper's no-snapshot overload, so Spigot can run this too.
                for (final BlockState state : chunk.getTileEntities())
                    if (state instanceof Hopper) hoppers++;
            }
        }

        return new Recommendations.Census(villagers, mobs, Bukkit.getOnlinePlayers().size(), chunks, hoppers, items, minecarts);
    }

    private List<Recommendations.Item> recommend(ScanResult scan) {
        final Map<String, OptimizationLevel> pending = new HashMap<>();
        scan.findings().keySet().forEach(o -> pending.put(o.id(), o.level()));

        Double perRealClient = null;
        final Row real = this.row("Real clients");

        if (real != null && this.bots != null && this.bots.peakConnected() > 0)
            perRealClient = Math.max(0, real.deltaMs()) / this.bots.peakConnected();

        final List<Recommendations.Item> items = Recommendations.build(new Recommendations.Inputs(
                this.benchBaseMs, this.perUnit("Villagers"), this.perUnit("Cows"), this.perUnit("Redstone clocks"),
                this.perUnit("Player footprint"), perRealClient,
                (this.footprintRadius * 2 + 1) * (this.footprintRadius * 2 + 1), median(this.chunkGenRuns),
                this.perUnit("Hoppers"), this.perUnit("Dropped items"), this.perUnit("Minecarts"),
                this.perUnit("Explosions"), this.census, pending,
                this.plugin.getConfig().getBoolean("runtime.mob-limiter.enabled", true)));

        // A roaming baseline is the live server, so "unexplained load" is the whole tick - drop it.
        if (this.roamAround != null) items.removeIf(i -> i.title().equals("Unexplained load"));

        if (items.isEmpty()) {
            this.say(ChatColor.GREEN + "Recommendations: " + ChatColor.GRAY + "nothing stands out. Your worlds cost well under what this server can take.");
            return items;
        }
        this.say(ChatColor.AQUA + "Recommendations " + ChatColor.GRAY + "(biggest estimated cost first)");
        int n = 0;

        for (final Recommendations.Item item : items) {
            n++;
            final String cost = Double.isNaN(item.estimatedMs()) ? "" : String.format(" " + ChatColor.GRAY + "~%.1fms/tick", item.estimatedMs());
            this.say(String.format(ChatColor.WHITE + "  %d. %s%s  " + ChatColor.DARK_GRAY + "%s", n, item.title(), cost, item.evidence()));
            this.say(ChatColor.GRAY + "     " + item.fix());

            if (item.command() != null) {
                this.report.add("       -> " + item.command());
                // Most items apply a setting; the "unexplained load" one points at a profile to run instead.
                final String label = item.command().startsWith("/catalyst config") ? "[apply this]" : "[run this]";
                this.plugin.chatLinks().send(this.sender, ChatColor.GRAY + "     ", ChatColor.AQUA + label, "",
                        item.command(), "", ChatLinks.Click.SUGGEST);
            }
        }
        this.say(ChatColor.DARK_GRAY + "  Estimates multiply this benchmark's per-unit costs by what your worlds hold right now.");
        return items;
    }

    private Row row(String name) {
        return this.rows.stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
    }

    private Double perUnit(String name) {
        final Row r = this.row(name);

        return r == null ? null : Math.max(0, r.deltaMs()) / Math.max(1, r.units());
    }

    /**
     * The point of the bot stage: how far the cheap footprint estimate is from what a
     * connected client really costs, so the footprint figures can be read with that gap in mind.
     */
    private void compareFootprintWithClients() {
        if (this.bots == null) return;
        final Row real = this.rows.stream().filter(r -> r.name().equals("Real clients")).findFirst().orElse(null);

        if (real == null) return;
        final int peak = this.bots.peakConnected();
        this.say(String.format(ChatColor.DARK_GRAY + "  Real clients: %d bots connected at peak and received %,d chunks%s%s.",
                peak, this.bots.chunks(), this.bots.client().isEmpty() ? "" : " (" + this.bots.client() + ")",
                this.bots.forwardingLabel() == null ? "" : ", joined through " + this.bots.forwardingLabel()));
        this.say(ChatColor.DARK_GRAY + "  Their own CPU runs in a separate process, so it is not in the figure.");

        if (this.roamAround != null) {
            this.say(ChatColor.DARK_GRAY + "  They roamed the world \"" + this.roamAround.getWorld().getName() + "\" around "
                    + (this.sender instanceof Player ? "where you stood" : "its spawn")
                    + (this.roamSpread ? ", each at its own spot up to " + BotStage.spreadRadius(this.plugin) + " blocks away" : "")
                    + ", walking and jumping on routes planned"
                    + " through its terrain, measured against the world as it was just before.");

        if (this.nearbyEntities >= 0)
            this.say(String.format(ChatColor.DARK_GRAY + "  %,d entities (%,d mobs) were within tracking range of the start, before the bots joined.",
                    this.nearbyEntities, this.nearbyMobs));

        // Clustered bots are themselves the entities the server tracks: 60 bots in one spot are ~60
        // players each tracking ~59 others, which dominates the entity-tracking figure. Spreading
        // them out takes almost all of that away, so a high figure on a here run is expected.
            // A crowd costs differently from a spread-out server: clustered here, most of the
            // entity-tracking cost is the bots tracking each other, and they share chunks. Worth
            // saying which was measured and how to get the other.
            if (!this.roamSpread && this.bots.count() >= 10)
                this.say(ChatColor.DARK_GRAY + "  They were all in one area, like players at an event, so most of the entity-tracking"
                        + " cost is the bots tracking each other, not the world. For players spread over a world,"
                        + " run /catalyst perf bench bots " + this.bots.count() + " spread.");
        } else {
            this.say(ChatColor.DARK_GRAY + "  Each walked its own lane, so no two bots ever loaded the same chunks.");
        }

        if (this.mirrorAsked.get() > 0)
            this.say(String.format(ChatColor.DARK_GRAY + "  Their %,d new chunks were also generated with real terrain (%s) at the same spots,"
                    + " %,d of them finished by the end, so the figure includes exploring real terrain.",
                    this.mirrorAsked.get(), this.terrainFrom.name(), this.mirrorDone.get()));
        else if (this.terrain == null && this.terrainSkip != null)
            this.say(ChatColor.DARK_GRAY + "  Their new chunks were flat only - " + this.terrainSkip + " - so exploring real terrain would cost more.");
        else if (!PaperWorlds.available())
            this.say(ChatColor.DARK_GRAY + "  Their new chunks were flat only: mirroring them into real terrain needs Paper, which generates"
                    + " chunks off the server thread; here it would stall the server. Exploring real terrain costs more.");

        if (this.bots.metadataKey() != null)
            this.say(ChatColor.DARK_GRAY + "  " + this.bots.taggedCount() + " bots carried the metadata \"" + this.bots.metadataKey()
                    + "\", so other plugins could tell them apart; it came off as they left.");

        if (this.bots.skinnedCount() >= 0)
            this.say(ChatColor.DARK_GRAY + "  " + this.bots.skinnedCount() + " bots wore skins from benchmark.bots.skins.");

        final Row footprint = this.rows.stream().filter(r -> r.name().equals("Player footprint")).findFirst().orElse(null);

        if (footprint == null || peak == 0) return;
        final double estimated = Math.max(0, footprint.deltaMs()) / Math.max(1, footprint.units());
        final double actual = Math.max(0, real.deltaMs()) / peak;
        final String ratio = estimated >= 0.01 ? String.format(" " + ChatColor.DARK_GRAY + "(%.1fx the estimate)", actual / estimated) : "";
        this.say(String.format(ChatColor.GRAY + "  One player: estimated " + ChatColor.WHITE + "+%.2fms" + ChatColor.GRAY + ", a real client " + ChatColor.WHITE + "+%.2fms" + ChatColor.GRAY + "/tick%s",
                estimated, actual, ratio));
    }

    /** The specific things the bot run found on this server; see BotFindings. */
    private void reportBotFindings() {
        if (this.bots == null || this.row("Real clients") == null) return;
        final Set<String> ignore = Set.of(this.plugin.getName().toLowerCase(Locale.ROOT), "spark");
        final List<BotFindings.Finding> found = BotFindings.find(this.botProfile, Math.max(1, this.bots.peakConnected()), ignore,
                this.mirrorAsked.get(), this.mirrorDone.get(), this.chunksGenerated.get(), this.chunksLoaded.get(),
                this.roamAround != null && !this.roamSpread && this.bots.peakConnected() >= 10);

        if (!found.isEmpty()) {
            this.say(ChatColor.AQUA + "Found during the bot run");

            for (final BotFindings.Finding f : found) {
                this.say(ChatColor.WHITE + "  - " + f.title());
                this.say(ChatColor.DARK_GRAY + "      " + f.evidence());
                this.say(ChatColor.GRAY + "      " + f.advice());
            }
        } else if (this.botProfile != null) {
            this.say(ChatColor.GRAY + "  Found during the bot run: " + ChatColor.GREEN + "nothing specific" + ChatColor.GRAY + " - no plugin or chunk work stood out.");
        }

        // Not on plain CraftBukkit, where the current spark cannot start (see SparkInstaller).
        if (this.botProfile == null && !SparkSupport.isAvailable() && hasSpigotApi())
            this.say(ChatColor.DARK_GRAY + "  With spark, the bot run also names the plugins that cost the most per player"
                    + " (/catalyst perf spark install).");
        this.reportBotProfile();
    }

    /**
     * The whole tick's breakdown while the bots ran, from the profile the stage takes anyway. The
     * findings above only call out what crosses a threshold; this shows where all of the time went,
     * including load that is the server's own rather than the bots', so "why is it this slow" has an
     * answer in the report itself rather than needing a second command.
     */
    private void reportBotProfile() {
        if (this.botProfile == null) return;
        final ProfileAnalysis.Result p = this.botProfile;
        this.say(ChatColor.AQUA + "Where the tick went with the bots on "
                + ChatColor.GRAY + String.format(Locale.ROOT, "(%.0fs profiled)", p.durationSeconds()));

        // spark's category is "loading and generation", but with nothing generated the cost is
        // loading from disk and sending to the clients; name it for what it was.
        final boolean sending = this.chunksGenerated.get() < 50 && this.chunksLoaded.get() > 0;
        int shown = 0;

        for (final ProfileAnalysis.Share c : p.categories()) {
            if (c.perTick() < 0.5 || shown >= 7) break;
            shown++;
            final String name = sending && c.name().equals("Chunk loading and generation")
                    ? "Chunk loading and sending" : c.name();
            this.say(String.format(Locale.ROOT, ChatColor.GRAY + "  %-30s " + ChatColor.WHITE + "%6.2fms" + ChatColor.GRAY + "/tick "
                    + ChatColor.DARK_GRAY + "(%.0f%%)", name, c.perTick(), c.percent()));

            // A big unnamed share says nothing on its own, so name what was inside it.
            if (c.name().equals("Other server work") && c.percent() >= 15)
                for (final ProfileAnalysis.HotMethod m : p.otherParts().subList(0, Math.min(3, p.otherParts().size())))
                    if (m.percent() >= 2)
                        this.say(String.format(Locale.ROOT, ChatColor.DARK_GRAY + "      %-26s %6.2fms/tick",
                                m.frame(), m.millis() / Math.max(1, p.ticks())));
        }

        for (final ProfileAnalysis.Share pl : p.plugins()) {
            if (pl.perTick() < 0.25) break;

            // Catalyst's own time here is planning the roaming bots' routes on the server thread -
            // work real players do on their own clients, so it is not part of what a player costs.
            final String note = this.roamAround != null && pl.name().equalsIgnoreCase(this.plugin.getName())
                    ? ChatColor.DARK_GRAY + "  (routing the bots, not a player cost)" : "";
            this.say(String.format(Locale.ROOT, ChatColor.GRAY + "  plugin %-23s " + ChatColor.WHITE + "%6.2fms" + ChatColor.GRAY + "/tick%s",
                    pl.name(), pl.perTick(), note));
        }

        if (shown == 0 && p.plugins().isEmpty())
            this.say(ChatColor.DARK_GRAY + "  Nothing took a clear share - the load is spread thin across many small things.");
        this.say(ChatColor.DARK_GRAY + "  Run /catalyst perf profile while the load is on for the full call tree and methods.");
    }

    private static boolean hasSpigotApi() {
        try {
            Class.forName("org.bukkit.entity.Player$Spigot");
            return true;
        } catch (ClassNotFoundException | LinkageError plainCraftBukkit) {
            return false;
        }
    }

    /**
     * A readable one-line summary of the run. The raw Settings record lists every stage, which for
     * a bots-only run is a wall of zeros that says nothing; this names what the run actually did.
     */
    private String settingsLine() {
        final int repeats = this.settings.repeats();
        final String runs = repeats + " run" + (repeats == 1 ? "" : "s");

        if (this.bots != null && !this.hasLoadStages()) {
            final String where = this.roamAround != null
                    ? (this.roamSpread ? "spread out in \"" : "roaming \"") + this.roamAround.getWorld().getName() + "\""
                    : "in a bench world";
            return this.bots.count() + " bots " + where + ", " + runs + " each";
        }
        final String stages = "intensity " + this.settings.intensity().name().toLowerCase(Locale.ROOT)
                + ", " + runs + " per stage";

        return this.bots != null ? stages + ", plus " + this.bots.count() + " bots" : stages;
    }

    /** Whether any load stage runs, as opposed to a bots-only run where they are all zero. */
    private boolean hasLoadStages() {
        final Settings s = this.settings;

        return s.villagers() > 0 || s.cows() > 0 || s.clocks() > 0 || s.hoppers() > 0 || s.items() > 0
                || s.pistons() > 0 || s.minecarts() > 0 || s.explosions() > 0 || s.footprints() > 0
                || s.generatedChunks() > 0;
    }

    private void saveAndUpload(List<String> chatLines) {
        final LocalDateTime now = LocalDateTime.now();
        final File out = new File(this.plugin.getDataFolder(),
                "reports/bench-" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".txt");

        final List<String> text = new ArrayList<>();
        text.add(Branding.name() + " benchmark - " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        text.add("Server: " + Bukkit.getName() + " " + Bukkit.getVersion());
        text.add("Run: " + this.settingsLine());
        text.add("");

        for (final String line : chatLines) text.add(line.replaceAll("§.", ""));
        text.add("");
        text.add("Individual runs (ms/tick; stages are the difference against the " + (this.roamAround != null ? "world as it was" : "empty world") + "):");
        text.add(String.format("  %-18s %s", this.roamAround != null ? "Your world as is" : "Empty bench world", fmt(this.baseRuns)));

        for (final Row row : this.rows) text.add(String.format("  %-18s %s", row.name(), fmt(row.runs())));

        if (!this.chunkGenRuns.isEmpty()) text.add(String.format("  %-18s %s (ms per chunk)", "Chunk generation", fmt(this.chunkGenRuns)));

        try {
            Files.createDirectories(out.getParentFile().toPath());
            Files.write(out.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            this.sender.sendMessage(ChatColor.GRAY + "Could not save the report: " + e.getMessage());
            return;
        }
        this.sender.sendMessage(ChatColor.GRAY + "Report saved to " + ChatColor.WHITE + "plugins/" + Branding.name() + "/reports/" + out.getName());

        if (this.plugin.uploader().isEnabled() && this.plugin.uploader().isConfigured()) {
            final UploadResult result = this.plugin.uploader().uploadFile(out.getName(), out, "text/plain");

            if (result.success()) {
                for (final String url : result.urls())
                    this.plugin.chatLinks().send(this.sender, ChatColor.GRAY + "Report: ", "§b" + url, "", url,
                            "Open the report in your browser", ChatLinks.Click.OPEN_URL);
            } else {
                this.sender.sendMessage(ChatColor.GRAY + "Upload skipped: " + result.message());
            }
        }
        this.chatSep();
    }

    private static String fmt(List<Double> values) {
        return Arrays.toString(values.stream().map(v -> String.format("%.3f", v)).toArray());
    }

    /** Unloads and deletes the bench world. Safe to call more than once. */
    public void cleanup() {
        this.stopChunkGen();
        this.stopChunkCount();
        this.stopMemoryWatch();

        if (this.tpsMonitor != null) this.tpsMonitor.cancel();

        if (this.blastTask != null) this.blastTask.cancel();

        if (this.blastListener != null) HandlerList.unregisterAll(this.blastListener);

        if (this.pulseWatch != null) this.pulseWatch.cancel();
        this.pulseWatch = null;

        if (this.pistonWatch != null) this.pistonWatch.cancel();
        this.pistonWatch = null;
        this.stopMirroring();

        if (this.bots != null) this.bots.kill();
        final World flat = this.world != null ? this.world : Bukkit.getWorld(WORLD_NAME);
        final World copy = this.terrain != null ? this.terrain : Bukkit.getWorld(TERRAIN_NAME);
        this.world = null;
        this.terrain = null;
        this.close(flat, WORLD_NAME, this.exit);
        this.close(copy, TERRAIN_NAME, this.terrainExit);
        this.exit = this.terrainExit = null;
    }

    /** Unloads and deletes one bench world, returning anyone inside it first. */
    private void close(World w, String name, BenchExit exit) {
        if (w == null) {
            deleteFolder(name);

            if (this.exit != null) this.exit.unregister();
            return;
        }

        for (final Entity e : w.getEntities()) if (!(e instanceof Player)) e.remove();

        // A world with players in it cannot be unloaded, and its folder must not be deleted
        // while loaded. Nobody is kicked by name - the bots were already stopped by their own
        // stage, so whoever is left is a person - and nobody is sent somewhere arbitrary:
        // each goes back to where they came from, or to their own respawn point.
        final List<Player> stuck = this.exit == null ? w.getPlayers() : this.exit.evacuate();

        if (!stuck.isEmpty()) {
            final String who = String.join(", ", stuck.stream().map(Player::getName).toList());
            this.plugin.getLogger().info(who + " stayed in " + name + ": there was no recorded place to return them to, or the"
                    + " teleport was cancelled. It is deleted once they leave, or on the next start.");

            if (this.exit != null && this.plugin.isEnabled()) {
                this.exit.whenEmpty(() -> {
                    if (Bukkit.unloadWorld(w, false)) deleteFolder(name);
                    this.exit.unregister();
                });
            }
            return;
        }

        if (this.exit != null) this.exit.unregister();

        // If the unload fails anyway, the folder is removed on the next start instead.
        if (Bukkit.unloadWorld(w, false)) deleteFolder(name);
    }

    /** Returns anyone in the bench worlds to where they came from; each lives as long as its world. */
    private BenchExit exit;

    /** Removes bench worlds left behind by a crash or a stop mid-run. */
    public static void deleteLeftover() {
        for (final String name : List.of(WORLD_NAME, TERRAIN_NAME))
            if (Bukkit.getWorld(name) == null) deleteFolder(name);
    }

    /** Written into each bench world when Catalyst creates it. */
    private static final String OWNER_MARKER = "catalyst-bench.marker";

    /**
     * pause-when-empty-seconds from server.properties, or 0 when it is missing or unreadable
     * (servers before 1.21.2 never pause). Bukkit has no getter for it.
     */
    static int pauseWhenEmptySeconds() {
        final Properties props = new Properties();

        try (final Reader in = Files.newBufferedReader(Path.of("server.properties"), StandardCharsets.UTF_8)) {
            props.load(in);
            return Integer.parseInt(props.getProperty("pause-when-empty-seconds", "0").trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** Every place a bench world can be on disk - beside the main save (1.21) or in it (26) - if Catalyst marked it. */
    private static void deleteFolder(String name) {
        for (final File candidate : WorldPaths.candidates(name)) {
            final Path dir = candidate.toPath();

            if (!Files.exists(dir)) continue;

            // An admin's own world that happens to share the name is never touched.
            if (!new File(candidate, OWNER_MARKER).isFile()) continue;

            try (final Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // Retried on the next start.
            }
        }
    }

    private World createWorld() {
        // FLAT with no settings makes vanilla log "No key layers" as an error before
        // falling back, so the layers are spelled out.
        final World w = new WorldCreator(WORLD_NAME)
                .type(WorldType.FLAT)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
                        + "{\"block\":\"minecraft:dirt\",\"height\":2},"
                        + "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}")
                .generateStructures(false)
                .createWorld();

        if (w == null) return null;
        this.mark(w);
        this.activationLifted = liftActivationRange(w);
        setRule(w, false, "spawn_mobs", "doMobSpawning");
        setRule(w, false, "advance_time", "doDaylightCycle");
        setRule(w, false, "advance_weather", "doWeatherCycle");

        // Otherwise vanilla kills crowded mobs mid-stage and the load shrinks while measured.
        setRule(w, 0, "max_entity_cramming", "maxEntityCramming");
        w.setTime(6000);

        return w;
    }

    /** Whether the bench world's mobs tick at full rate, for the report. */
    private boolean activationLifted;

    /**
     * Spigot and Paper tick a mob only now and then when no player is within its entity
     * activation range, and nobody stands in the bench world - so its mobs would be measured
     * at that far-off rate, well below what a farm with players at it costs. A range of 0
     * means always active. Each world keeps its ranges in its own settings object, read from
     * spigot.yml when it loads; setting them to 0 on the bench world's object changes that
     * world alone, and nothing is written anywhere. Plain CraftBukkit has no activation
     * range, so there is nothing to lift. False when the object could not be reached.
     */
    private static boolean liftActivationRange(World w) {
        try {
            final Object level = w.getClass().getMethod("getHandle").invoke(w);
            Field settings = null;

            for (Class<?> c = level.getClass(); c != null && settings == null; c = c.getSuperclass())
                for (final Field f : c.getDeclaredFields())
                    if (f.getName().equals("spigotConfig")) settings = f;

            if (settings == null) return false;
            settings.setAccessible(true);
            final Object ranges = settings.get(level);
            int lifted = 0;

            for (final Field f : ranges.getClass().getFields())
                if (f.getType() == int.class && f.getName().endsWith("ActivationRange")) {
                    f.setInt(ranges, 0);
                    lifted++;
                }
            return lifted > 0;
        } catch (Throwable noSpigot) {
            return false;
        }
    }
    /** Claims a world's folder as Catalyst's own: only a folder carrying this is ever deleted. */
    private void mark(World w) {
        try {
            Files.writeString(new File(w.getWorldFolder(), OWNER_MARKER).toPath(),
                    "Created by " + Branding.name() + " for /catalyst perf bench. Safe to delete.\n");
        } catch (IOException e) {
            // Without the marker the folder is never deleted; better a leftover than a mistake.
            this.plugin.getLogger().warning("Could not mark " + w.getName() + " as " + Branding.name() + "'s: " + e.getMessage());
        }
    }

    /**
     * Makes the real-terrain world: a new world with the real world's seed, generator and
     * settings, so its chunks are generated exactly as the real world's would be. Nothing in
     * the real world is loaded, generated or changed.
     */
    private void setUpTerrain() {
        final String configured = this.plugin.getConfig().getString("benchmark.terrain-world", "");
        this.terrainFrom = RealWorld.choose(RealWorld.loaded(), configured);

        if (this.terrainFrom == null) {
            this.terrainSkip = RealWorld.refusal(configured);
            return;
        }
        final World real = Bukkit.getWorld(this.terrainFrom.name());

        if (real == null) {
            this.terrainSkip = this.terrainFrom.name() + " is no longer loaded";
            return;
        }

        // Nothing of the world is copied - no region files, no builds - only what generates its
        // terrain: seed, generator and type. The new world starts empty and grows only by the
        // chunks the benchmark itself generates, however big the original is.
        this.sender.sendMessage(ChatColor.DARK_GRAY + "  making an empty world from " + real.getName() + "'s seed and generator, to time"
                + " real terrain (chosen as " + this.terrainFrom.why() + ")...");
        final WorldCreator creator = new WorldCreator(TERRAIN_NAME).copy(real);
        this.terrain = creator.createWorld();

        if (this.terrain == null) {
            this.terrainSkip = "a world from " + real.getName() + "'s seed could not be created";
            return;
        }
        this.mark(this.terrain);

        // Generated, never played in: nothing should spawn or change while it is timed.
        setRule(this.terrain, false, "spawn_mobs", "doMobSpawning");
        setRule(this.terrain, false, "advance_time", "doDaylightCycle");
        setRule(this.terrain, false, "advance_weather", "doWeatherCycle");
        setRule(this.terrain, 0, "spawn_chunk_radius", "spawnChunkRadius");
        this.terrainExit = new BenchExit(this.plugin, this.terrain);
        this.terrainExit.register();
    }

    /**
     * Sets a game rule by name rather than by constant: 1.21.11 renamed the rules
     * (doMobSpawning became spawn_mobs) and Spigot removed the old constants, so linking
     * to either set breaks the other half of the supported versions. The new name is tried
     * first, then the old one.
     */
    @SuppressWarnings({"unchecked", "deprecation", "removal"}) // getByName: the one lookup every version has
    private static <T> void setRule(World w, T value, String... names) {
        for (final String name : names) {
            GameRule<?> rule;

            try {
                rule = GameRule.getByName(name);
            } catch (IllegalArgumentException invalidKeyOnThisVersion) {
                continue;
            }

            if (rule != null && rule.getType().isInstance(value)) {
                w.setGameRule((GameRule<T>) rule, value);
                return;
            }
        }
    }
}

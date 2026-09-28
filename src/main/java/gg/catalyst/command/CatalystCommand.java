// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.command;

import gg.catalyst.Catalyst;
import gg.catalyst.bench.Benchmark;
import gg.catalyst.bench.bots.BotStage;
import gg.catalyst.chat.ChatLinks;
import gg.catalyst.netty.EventLoopSampler;
import gg.catalyst.netty.HandlerInfo;
import gg.catalyst.netty.NettyInspector;
import gg.catalyst.optimization.ApplyResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.optimization.ScanResult;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.report.Rating;
import gg.catalyst.report.ReportRenderer;
import gg.catalyst.runtime.RuntimeModule;
import gg.catalyst.spark.FoliaHotspots;
import gg.catalyst.spark.SparkInstaller;
import gg.catalyst.spark.SparkSupport;
import gg.catalyst.spark.WorldHotspots;
import gg.catalyst.tps.FoliaTicks;
import gg.catalyst.tps.RegionReport;
import gg.catalyst.util.Branding;
import gg.catalyst.util.ConfigWriter;
import gg.catalyst.util.PluginInfo;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

public final class CatalystCommand implements CommandExecutor, TabCompleter {
    private static final String BRAND = "§b";
    private static final String SAFE = "§a";
    private static final String WARN = "§e";
    private static final String ERR = "§c";
    private static final String MUTED = "§7";
    private static final String VALUE = "§f";

    private final Catalyst plugin;

    private static void sep(CommandSender s) { s.sendMessage(Branding.SEP); }

    private static boolean hasFlag(String[] args, String flag) {
        for (final String a : args) if (a.equalsIgnoreCase("-" + flag)) return true;

        return false;
    }

    /** Parses seconds and clamps to range, telling the sender if it did. Null if not a number. */
    private static Integer seconds(CommandSender sender, String raw, int min, int max) {
        // BigInteger, so digits too many for an int are still clamped rather than refused.
        if (!raw.matches("[+-]?\\d+")) {
            sep(sender);
            sender.sendMessage(ERR + "'" + raw + "' is not a number of seconds.");
            sep(sender);
            return null;
        }
        final BigInteger asked = new BigInteger(raw);
        final boolean tooShort = asked.compareTo(BigInteger.valueOf(min)) < 0;
        final boolean tooLong = asked.compareTo(BigInteger.valueOf(max)) > 0;
        final int used = tooShort ? min : tooLong ? max : asked.intValue();

        if (tooShort || tooLong)
            sender.sendMessage(WARN + (tooShort ? "Shortest" : "Longest") + " allowed is " + used
                    + "s, so running for " + used + "s instead of " + raw + "s.");

        return used;
    }

    /** Returns a copy of args with all -flag tokens removed. */
    private static String[] positional(String[] args) {
        return Arrays.stream(args).filter(a -> !a.startsWith("-")).toArray(String[]::new);
    }

    public CatalystCommand(Catalyst plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            this.help(sender, label);
            return true;
        }

        // Each group strips its own name, so handlers still see [subcommand, arg...].
        final String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (args[0].toLowerCase()) {
            case "config" -> this.config(sender, label, rest);
            case "perf" -> this.perf(sender, label, rest);
            case "network" -> this.networkGroup(sender, label, rest);
            case "modules" -> {
                if (rest.length == 0) this.modules(sender);
                else this.toggleModule(sender, rest);
            }
            case "status" -> this.status(sender);
            case "reload" -> this.reload(sender);
            default -> this.help(sender, label);
        }

        return true;
    }

    private void config(CommandSender sender, String label, String[] args) {
        final String sub = args.length == 0 ? "" : args[0].toLowerCase();

        switch (sub) {
            case "check" -> this.check(sender, hasFlag(args, "upload"));
            case "apply" -> this.apply(sender, args);
            case "fix" -> this.fix(sender, args);
            case "backups" -> this.backups(sender, label + " config");
            case "restore" -> this.restore(sender, args, label + " config");
            default -> this.groupHelp(sender, label, "config");
        }
    }

    private void perf(CommandSender sender, String label, String[] args) {
        final String sub = args.length == 0 ? "" : args[0].toLowerCase();

        switch (sub) {
            case "tps" -> this.tps(sender);
            case "lag" -> this.lag(sender, args);
            case "profile" -> this.profile(sender, args);
            case "plugins" -> this.plugins(sender, args);
            case "stats" -> this.diagnose(sender);
            case "bench" -> this.bench(sender, args);
            case "spark" -> this.spark(sender, args);
            default -> this.groupHelp(sender, label, "perf");
        }
    }

    private void networkGroup(CommandSender sender, String label, String[] args) {
        final String sub = args.length == 0 ? "" : args[0].toLowerCase();

        switch (sub) {
            case "info" -> this.network(sender);

            // The pipeline handlers expect the old "netty" shape, so rebuild it.
            case "pipeline" -> this.netty(sender, prefixed("netty", args));
            case "profile" -> this.netty(sender, prefixed("netty", args));
            case "tps" -> this.networkTps(sender, args);
            default -> this.groupHelp(sender, label, "network");
        }
    }

    private volatile boolean loopSampleRunning = false;
    private volatile EventLoopSampler loopSampler;
    // So a cancelled sample's pending finish can't report after it was stopped.
    private final java.util.concurrent.atomic.AtomicInteger loopRuns = new java.util.concurrent.atomic.AtomicInteger();

    /** Samples every network event loop players use, then reports how late each one runs. */
    private void networkTps(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("cancel")) {
            this.cancelLoopSample(sender);
            return;
        }

        if (this.loopSampleRunning) {
            sep(sender);
            sender.sendMessage(ERR + "A network sample is already running. Stop it with /catalyst network tps cancel.");
            sep(sender);
            return;
        }
        int seconds = 10;

        if (args.length > 1) {
            final Integer asked = seconds(sender, args[1], 3, 60);

            if (asked == null) return;
            seconds = asked;
        }

        if (this.plugin.getServer().getOnlinePlayers().isEmpty()) {
            sep(sender);
            sender.sendMessage(MUTED + "Nobody is online, so there are no player connections to measure.");
            sep(sender);
            return;
        }
        this.loopSampleRunning = true;
        final int run = this.loopRuns.incrementAndGet();
        final int wait = seconds;
        this.plugin.runGlobal(() -> {
            final EventLoopSampler sampler =
                    EventLoopSampler.start(this.plugin.getServer().getOnlinePlayers());

            if (sampler.isEmpty()) {
                this.loopSampleRunning = false;
                sep(sender);
                sender.sendMessage(ERR + "Could not reach any player's connection on this server.");
                sep(sender);
                return;
            }
            // A cancel that landed before this global task ran already bumped the counter; if so,
            // stop the sampler we just started rather than leaving it attached with no report.
            if (this.loopRuns.get() != run) {
                sampler.stop();
                this.loopSampleRunning = false;
                return;
            }
            this.loopSampler = sampler;
            sep(sender);
            sender.sendMessage(BRAND + "Sampling the network threads for " + wait + "s.");
            this.plugin.chatLinks().send(sender, MUTED + "Changed your mind? ", WARN + "[/catalyst network tps cancel]", "",
                    "/catalyst network tps cancel", "Stop the sample without a report", ChatLinks.Click.RUN);
            sep(sender);
            this.plugin.runDelayed(() -> {
                // A cancel (or a newer sample) bumped the run counter, so this finish is stale.
                if (this.loopRuns.get() != run) return;

                try {
                    this.reportLoops(sender, sampler.stop());
                } finally {
                    this.loopSampleRunning = false;
                    this.loopSampler = null;
                }
            }, wait);
        });
    }

    /** Stops the network-thread sample at once and drops it, with no report. */
    private void cancelLoopSample(CommandSender sender) {
        sep(sender);

        if (!this.loopSampleRunning) {
            sender.sendMessage(MUTED + "No network sample is running.");
        } else {
            this.loopRuns.incrementAndGet();
            final EventLoopSampler sampler = this.loopSampler;

            if (sampler != null) this.plugin.runGlobal(sampler::stop);
            this.loopSampleRunning = false;
            this.loopSampler = null;
            sender.sendMessage(BRAND + "Network sample cancelled." + MUTED + " Nothing will be reported for it.");
        }
        sep(sender);
    }

    private void reportLoops(CommandSender sender, List<EventLoopSampler.LoopResult> results) {
        sep(sender);
        sender.sendMessage(BRAND + "Network threads " + MUTED + "(slowest first)");

        for (final EventLoopSampler.LoopResult r : results) {
            if (Double.isNaN(r.averageDelayMs())) {
                sender.sendMessage(String.format("%s  %-22s " + ChatColor.RED + "did not answer within 2s %s(%d player%s)",
                        ERR, r.thread(), MUTED, r.players(), r.players() == 1 ? "" : "s"));
                continue;
            }
            final Rating rating = Rating.forLoopDelayMillis(r.averageDelayMs());
            sender.sendMessage(String.format("  %s%-22s %s%.2fms " + ChatColor.GRAY + "late on average, " + ChatColor.WHITE + "%.1fms " + ChatColor.GRAY + "worst  %s~%.1f TPS  %s(%d player%s)",
                    VALUE, r.thread(), rating.color, r.averageDelayMs(), r.worstDelayMs(),
                    rating.color, r.tpsEquivalent(), MUTED, r.players(), r.players() == 1 ? "" : "s"));
        }
        sender.sendMessage(MUTED + "  green under 2ms | yellow under 10ms | red above (rated on the average;");
        sender.sendMessage(MUTED + "  single worst values near 15ms on Windows are its timer resolution, not lag)");
        sender.sendMessage(MUTED + "  A late thread delays every packet of every player on it. If one is red,");
        sender.sendMessage(MUTED + "  /catalyst network profile shows which handler on a connection is slow.");
        sep(sender);
    }

    /** Replaces the first argument, keeping the rest in place. */
    private static String[] prefixed(String first, String[] args) {
        String[] out = args.clone();

        if (out.length > 0 && out[0].equalsIgnoreCase("pipeline")) out[0] = first;
        else {
            final String[] shifted = new String[out.length + 1];
            shifted[0] = first;
            System.arraycopy(out, 0, shifted, 1, out.length);
            out = shifted;
        }

        return out;
    }

    private void bench(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            this.plugin.cancelBenchmark(sender);
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("skip")) {
            this.plugin.skipBenchmarkStage(sender, args.length >= 3 ? args[2] : "");
            return;
        }

        if (this.plugin.benchmarkRunning()) {
            sender.sendMessage(ERR + "A benchmark is already running. Stop it with /catalyst perf bench cancel.");
            return;
        }

        // /catalyst perf bench [bots [count] [here|spread]] [low|medium|harsh|extreme] [force], in any order after "bench".
        boolean force = false, here = false, spread = false;
        Integer botsOnly = null;
        Benchmark.Intensity intensity = null;

        for (int i = 1; i < args.length; i++) {
            final String a = args[i].toLowerCase();

            if (a.equals("force")) force = true;
            else if (a.equals("bots")) botsOnly = this.plugin.getConfig().getInt("benchmark.bots.count", 20);
            else if (botsOnly != null && a.matches("\\d{1,3}")) botsOnly = Integer.parseInt(a);
            else if (botsOnly != null && a.equals("here")) here = true;
            else if (botsOnly != null && a.equals("spread")) here = spread = true;
            else if (Benchmark.Intensity.parse(a) != null) intensity = Benchmark.Intensity.parse(a);
            else {
                sender.sendMessage(ERR + "Usage: /catalyst perf bench [bots [count] [here|spread]] [low|medium|harsh|extreme] [force]");
                return;
            }
        }
        final int maxBots = BotStage.maxBots(this.plugin);

        if (botsOnly != null && (botsOnly < 1 || botsOnly > maxBots)) {
            sender.sendMessage(ERR + "Bot count must be 1-" + maxBots + " (benchmark.bots.max-count).");
            return;
        }

        // Roaming bots gather round whoever asked; from the console, round the main world's spawn.
        final Location roam = !here ? null : sender instanceof Player p ? p.getLocation()
                : this.plugin.getServer().getWorlds().get(0).getSpawnLocation();
        this.plugin.startBenchmark(sender, force, botsOnly, intensity, roam, spread);
    }

    /** Folia without the spark-folia build: fall back to the busiest chunks around players. */
    private void foliaWhere(CommandSender sender) {
        sender.sendMessage(ERR + "Profiling needs spark. Folia switches its bundled spark off; install the Folia build,");
        this.plugin.chatLinks().send(sender, MUTED + "spark-folia — download from ",
                BRAND + "[ci.lucko.me/job/spark-extra-platforms]", "",
                "https://ci.lucko.me/job/spark-extra-platforms", "Download spark-folia", ChatLinks.Click.OPEN_URL);

        if (this.plugin.getServer().getOnlinePlayers().isEmpty()) {
            sender.sendMessage(MUTED + "Nobody is online to check around. " + VALUE + "/catalyst perf lag"
                    + MUTED + " finds busy chunks anywhere.");
            return;
        }
        sender.sendMessage(MUTED + "Checking the chunks around online players instead...");
        FoliaHotspots.aroundPlayers(this.plugin, 5, (crowded, hoppers) -> {
            if (crowded.isEmpty() && hoppers.isEmpty()) {
                sender.sendMessage(SAFE + "Nothing crowded near any player.");
            } else {
                sender.sendMessage(BRAND + "Busiest chunks near players");

                for (final WorldHotspots.Spot spot : crowded) this.whereLine(sender, spot);

                for (final WorldHotspots.Spot spot : hoppers) this.whereLine(sender, spot);
            }
            sender.sendMessage(MUTED + "  Only chunks near players can be read on Folia. For block activity");
            this.plugin.chatLinks().send(sender, MUTED + "  anywhere in the world: ", BRAND + "[/catalyst perf lag 30]", "",
                    "/catalyst perf lag 30", "Find which chunks are generating load", ChatLinks.Click.RUN);
        });
    }

    private void whereLine(CommandSender sender, WorldHotspots.Spot spot) {
        final String tp = "/execute in " + spot.world() + " run tp @s " + spot.blockX() + " ~ " + spot.blockZ();
        this.plugin.chatLinks().send(sender,
                String.format("  %s%d %s %sin chunk %d, %d (%s)  ", VALUE, spot.count(), spot.what(), MUTED,
                        spot.chunkX(), spot.chunkZ(), spot.world()),
                BRAND + "[teleport]", "", tp, "Teleport to the middle of this chunk", ChatLinks.Click.RUN);
    }

    /** Runs a full spark profile across every thread and explains where the tick went. */
    private void profile(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            this.plugin.cancelSparkProfile(sender);
            return;
        }

        if (!SparkSupport.isAvailable()) {
            if (PlatformDetector.isFolia()) {
                this.foliaWhere(sender);
                return;
            }
            sender.sendMessage(ERR + "Profiling needs spark, which is not on this server.");
            this.offerSpark(sender);
            return;
        }

        if (this.plugin.sparkProfileRunning()) {
            sender.sendMessage(ERR + "A profile is already running. Wait for it, or stop it with /catalyst perf profile cancel.");
            return;
        }

        final boolean upload = hasFlag(args, "upload");
        final String[] pos = positional(args);
        int seconds = 60;

        if (pos.length >= 2) {
            final Integer asked = seconds(sender, pos[1], 20, 600);

            if (asked == null) return;
            seconds = asked;
        }
        this.plugin.startSparkProfile(sender, seconds, false, upload);
    }

    /** Profiles, then lists the worst-performing plugins and their worst event listeners. */
    private void plugins(CommandSender sender, String[] args) {
        // The same profiler behind both, so either name cancels it.
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            this.plugin.cancelSparkProfile(sender);
            return;
        }

        if (!SparkSupport.isAvailable()) {
            sender.sendMessage(ERR + "This needs spark, which is not on this server.");
            this.offerSpark(sender);
            return;
        }

        if (this.plugin.sparkProfileRunning()) {
            sender.sendMessage(ERR + "A profile is already running. Wait for it, or stop it with /catalyst perf profile cancel.");
            return;
        }

        final boolean upload = hasFlag(args, "upload");
        final String[] pos = positional(args);
        int seconds = 60;

        if (pos.length >= 2) {
            final Integer asked = seconds(sender, pos[1], 20, 600);

            if (asked == null) return;
            seconds = asked;
        }
        this.plugin.startSparkProfile(sender, seconds, true, upload);
    }

    private List<String> levelNames() {
        return this.plugin.engine().levelsInUse().stream().map(l -> l.name().toLowerCase()).toList();
    }

    private void check(CommandSender sender, boolean upload) {
        sep(sender);
        sender.sendMessage(MUTED + "Scanning...");
        this.plugin.scanAsync(scan -> {
            final List<String> lines = ReportRenderer.detailed(scan);

            // When it will really upload, don't also dump the whole report to chat.
            if (upload && this.plugin.uploadReady()) {
                this.plugin.uploadReport(sender, lines, "catalyst-check");
                return;
            }

            if (scan.isClean() || !this.plugin.chatLinks().isClickable())
                for (final String line : lines) sender.sendMessage(line);
            else
                this.showCheckFindings(sender, scan);
            sep(sender);

            if (upload) this.plugin.uploadReport(sender, lines, "catalyst-check");
        });
    }

    /** config check in chat: each finding with a clickable [apply this], then apply-all buttons. */
    private void showCheckFindings(CommandSender sender, ScanResult scan) {
        sender.sendMessage(BRAND + Branding.name() + MUTED + "  " + scan.platform().displayName + " - performance scan");
        sender.sendMessage("");

        for (final var e : scan.findings().entrySet()) {
            for (final String line : ReportRenderer.finding(e.getKey(), e.getValue())) sender.sendMessage(line);
            this.plugin.chatLinks().send(sender, MUTED + "    ", BRAND + "[apply this]", "",
                    "/catalyst config fix " + e.getKey().id(), "", ChatLinks.Click.SUGGEST);
            sender.sendMessage("");
        }

        if (scan.count(OptimizationLevel.SAFE) > 0)
            this.plugin.chatLinks().send(sender, MUTED + "The safe changes: ", SAFE + "[apply all safe]", "",
                    "/catalyst config apply safe", "", ChatLinks.Click.SUGGEST);

        if (scan.count(OptimizationLevel.MODERATE) > 0)
            this.plugin.chatLinks().send(sender, MUTED + "The moderate ones too: ", WARN + "[apply all moderate]", "",
                    "/catalyst config apply moderate", "", ChatLinks.Click.SUGGEST);
    }

    private void apply(CommandSender sender, String[] args) {
        if (this.busy(sender)) return;
        final List<String> offered = this.levelNames();

        if (args.length < 2) {
            sep(sender);
            sender.sendMessage(ERR + "Specify a level: " + VALUE + String.join(ERR + " or " + VALUE, offered));
            sender.sendMessage(MUTED + "Run /catalyst config check first to see what each one would change.");
            sep(sender);
            return;
        }
        final String asked = args[1].toLowerCase();

        if (!offered.contains(asked)) {
            sep(sender);
            sender.sendMessage(ERR + "Unknown level. Use " + String.join(" or ", offered)
                    + ", or /catalyst config fix <id> for one setting.");
            sep(sender);
            return;
        }

        this.startApply(sender);
        this.plugin.applyAsync(OptimizationLevel.valueOf(asked.toUpperCase()), result -> this.reportApply(sender, result));
    }

    /** Applies named optimizations on their own (a recommendation's fix), not a whole level. */
    private void fix(CommandSender sender, String[] args) {
        if (this.busy(sender)) return;

        if (args.length < 2) {
            sep(sender);
            sender.sendMessage(ERR + "Specify one or more optimization ids (see /catalyst config check).");
            sep(sender);
            return;
        }
        final Set<String> valid = this.plugin.engine().optimizationIds();
        final Set<String> ids = new LinkedHashSet<>();
        final List<String> unknown = new ArrayList<>();

        for (int i = 1; i < args.length; i++)
            if (valid.contains(args[i])) ids.add(args[i]);
            else unknown.add(args[i]);

        if (!unknown.isEmpty()) {
            sep(sender);
            sender.sendMessage(ERR + "Unknown optimization id: " + String.join(", ", unknown));
            sender.sendMessage(MUTED + "See the ids in /catalyst config check.");
            sep(sender);
            return;
        }

        this.startApply(sender);
        this.plugin.applyOnlyAsync(ids, result -> this.reportApply(sender, result));
    }

    private void startApply(CommandSender sender) {
        sep(sender);
        sender.sendMessage(MUTED + "Backing up, writing config, applying what the server accepts live...");
    }

    private void reportApply(CommandSender sender, ApplyResult result) {
        for (final String err : result.errors()) sender.sendMessage(ERR + "  " + err);

        if (result.applied().isEmpty()) {
            sender.sendMessage(SAFE + "Nothing to change - already optimal.");
            sep(sender);
            return;
        }

        final List<Optimization> live = result.liveApplied();
        final List<Optimization> later = result.restartRequired();

        if (!live.isEmpty()) {
            sender.sendMessage(SAFE + "Active now (" + live.size() + "):");

            for (final Optimization opt : live) this.appliedLine(sender, opt);
        }

        if (!later.isEmpty()) {
            sender.sendMessage(WARN + "Saved to config, active after restart (" + later.size() + "):");

            for (final Optimization opt : later) this.appliedLine(sender, opt);
        }

        if (result.backupDir() != null)
            sender.sendMessage(MUTED + "Backup: plugins/" + Branding.name() + "/backups/" + result.backupDir().getName());
        this.plugin.chatLinks().send(sender, MUTED + "Changed your mind? ", BRAND + "[/catalyst config restore 1]", "",
                "/catalyst config restore 1", "", ChatLinks.Click.SUGGEST);
        sep(sender);
    }

    /** One applied setting, followed by the exact file and key it was written to. */
    private void appliedLine(CommandSender sender, Optimization opt) {
        final String file = opt.configFile();
        final String where = file.isEmpty() ? ""
                : MUTED + "  " + file + (opt.configKey().isEmpty() ? "" : " -> " + opt.configKey());
        sender.sendMessage(MUTED + "  - " + VALUE + opt.name() + where);
    }

    private void tps(CommandSender sender) {
        sep(sender);
        final var source = this.plugin.tpsSource();

        if (!source.isAvailable()) {
            sender.sendMessage(MUTED + "TPS is " + source.describe() + ".");
            sender.sendMessage(MUTED + "Everything else in " + Branding.name() + " still works.");
            sep(sender);
            return;
        }

        final double now = source.currentTps();

        if (Double.isNaN(now)) {
            sender.sendMessage(MUTED + "TPS reading is " + source.describe() + " - try again in a second.");
            sep(sender);
            return;
        }
        sender.sendMessage(BRAND + "TPS " + colorForTps(now) + String.format("%.2f", now)

                // Well above 20 means catch-up ticks after a stall, not a faster server.
                + MUTED + " (" + (now > 20.5 ? "catching up" : now >= 19.995 ? "perfect" : Rating.forTps(now).name().toLowerCase()) + ")"
                + MUTED + "  source: " + source.describe());

        if (PlatformDetector.isFolia()) {
            final var regions = FoliaTicks.regions();

            if (regions != null) RegionReport.send(sender, regions, this.plugin.chatLinks(), false);
        }

        final double lowest = this.plugin.tpsWatchdog().lowestSeen();

        if (!Double.isNaN(lowest))
            sender.sendMessage(MUTED + "  Lowest seen this session: " + colorForTps(lowest)
                    + String.format("%.2f", lowest) + MUTED + " (averaged, so a brief dip reads higher than it felt)");

        sender.sendMessage(MUTED + "  Watchdog: " + VALUE + this.plugin.tpsWatchdog().status());
        sep(sender);
    }

    /** Player latency and the settings that decide how much work each packet costs. */
    private void network(CommandSender sender) {
        sep(sender);
        final var players = this.plugin.getServer().getOnlinePlayers();
        sender.sendMessage(BRAND + "Network " + MUTED + "(" + players.size() + " player(s) online)");

        if (players.isEmpty()) {
            sender.sendMessage(MUTED + "  No players connected, so there is no latency to measure.");
        } else if (players.size() == 1) {
            // best/average/worst is a spread across players; with one online it is just their ping.
            final var only = players.iterator().next();
            final int ping = only.getPing();
            sender.sendMessage(MUTED + "  Ping: " + colorForPing(ping) + ping + "ms"
                    + MUTED + " (" + only.getName() + ")");
        } else {
            int worst = Integer.MIN_VALUE, best = Integer.MAX_VALUE;
            long total = 0;
            String worstName = "";

            for (final var player : players) {
                final int ping = player.getPing();
                total += ping;

                if (ping > worst) { worst = ping; worstName = player.getName(); }

                if (ping < best) best = ping;
            }
            final int average = (int) (total / players.size());

            sender.sendMessage(MUTED + "  Ping across " + players.size() + " players: " + colorForPing(best) + best + "ms"
                    + MUTED + " best, " + colorForPing(average) + average + "ms"
                    + MUTED + " average, " + colorForPing(worst) + worst + "ms"
                    + MUTED + " worst (" + worstName + ")");
        }

        final String compression = this.plugin.engine().serverProperty("network-compression-threshold", "unknown");
        sender.sendMessage(MUTED + "  Compression threshold: " + VALUE + compression
                + MUTED + ("-1".equals(compression) ? " (disabled)" : " bytes"));
        sender.sendMessage(MUTED + "  Behind a proxy: " + VALUE + yesNo(this.plugin.engine().behindProxy()));

        // Just the network findings; config check would bury them in tick settings.
        this.plugin.scanAsync(scan -> {
            final List<Optimization> network = scan.findings().keySet().stream()
                    .filter(o -> "network".equals(o.category()))
                    .toList();

            if (network.isEmpty()) {
                sender.sendMessage(SAFE + "  Network settings look fine.");
                sep(sender);
                return;
            }
            sender.sendMessage(VALUE + "  " + network.size() + " network setting(s) worth changing:");
            final boolean clickable = this.plugin.chatLinks().isClickable();

            for (final Optimization opt : network) {
                final var result = scan.findings().get(opt);
                sender.sendMessage(MUTED + "    - " + VALUE + opt.name()
                        + MUTED + "  " + result.currentValue() + " -> " + result.recommendedValue()
                        + "  " + colorForLevel(opt) + "[" + opt.level().name().toLowerCase() + "]");

                if (clickable)
                    this.plugin.chatLinks().send(sender, MUTED + "      ", BRAND + "[apply this]", "",
                            "/catalyst config fix " + opt.id(), "", ChatLinks.Click.SUGGEST);
            }

            if (clickable && network.size() > 1)
                this.plugin.chatLinks().send(sender, MUTED + "    Or all of them: ", SAFE + "[apply all]", "",
                        "/catalyst config fix " + String.join(" ", network.stream().map(Optimization::id).toList()), "", ChatLinks.Click.SUGGEST);
            else if (!clickable)
                sender.sendMessage(MUTED + "    Apply with /catalyst config fix <id> (ids from /catalyst config check).");
            sep(sender);
        });
    }

    private static String colorForLevel(Optimization opt) {
        return switch (opt.level()) {
            case SAFE -> SAFE;
            case MODERATE -> WARN;
            case EXPERIMENTAL -> ERR;
        };
    }

    /** What's in a player's packet pipeline and who put it there. Server thread: reads connection internals. */
    private void netty(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("profile")) {
            this.nettyProfile(sender, args);
            return;
        }

        Player target = null;

        if (args.length >= 2) {
            target = this.plugin.getServer().getPlayerExact(args[1]);

            if (target == null) {
                sender.sendMessage(ERR + "No online player called '" + args[1] + "'.");
                return;
            }
        } else {
            for (final Player online : this.plugin.getServer().getOnlinePlayers()) { target = online; break; }
        }

        if (target == null) {
            sep(sender);
            sender.sendMessage(MUTED + "A pipeline belongs to a connection, so someone has to be online.");
            sender.sendMessage(MUTED + "Join the server, then run /catalyst network pipeline [player].");
            sep(sender);
            return;
        }

        final Player player = target;
        this.plugin.runGlobal(() -> {
            List<HandlerInfo> handlers;

            try {
                handlers = NettyInspector.inspect(player);
            } catch (NettyInspector.Unavailable e) {
                sender.sendMessage(ERR + "Cannot inspect the pipeline: " + e.getMessage());
                sender.sendMessage(MUTED + "Everything else in " + Branding.name() + " is unaffected.");
                return;
            }

            sep(sender);
            sender.sendMessage(BRAND + "Netty pipeline " + MUTED + "(" + player.getName()
                    + ", " + handlers.size() + " handler(s))");

            final Set<String> plugins = new LinkedHashSet<>();

            for (final HandlerInfo handler : handlers) {
                if (handler.fromPlugin()) {
                    plugins.add(handler.pluginName());
                    this.plugin.chatLinks().hover(sender,
                            WARN + "  " + handler.name() + MUTED + "  " + handler.simpleClassName() + "  ",
                            VALUE + "[" + handler.pluginName() + "]", "",
                            PluginInfo.hover(handler.pluginName()));
                } else {
                    sender.sendMessage(MUTED + "  " + handler.name() + "  " + handler.simpleClassName());
                }
            }

            sender.sendMessage("");

            if (plugins.isEmpty()) {
                sender.sendMessage(SAFE + "  No plugin has injected a packet handler.");
            } else {
                sender.sendMessage(VALUE + "  " + plugins.size() + " plugin(s) in the packet path: "
                        + String.join(", ", plugins));
                sender.sendMessage(MUTED + "  Handlers run on the network thread, so a slow one delays");
                sender.sendMessage(MUTED + "  every packet on this connection. Suspect these first if");
                sender.sendMessage(MUTED + "  players lag while TPS looks healthy.");
            }
            sep(sender);
        });
    }

    /** Times each handler on the caller's own connection only, so nobody else's packets are touched. */
    private void nettyProfile(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("cancel")) {
            this.plugin.cancelNettyProfile(sender);
            return;
        }

        if (!(sender instanceof Player player)) {
            sep(sender);
            sender.sendMessage(ERR + "Profiling measures a real connection, so run this in game.");
            sep(sender);
            return;
        }

        if (this.plugin.nettyProfileRunning()) {
            sender.sendMessage(ERR + "A profile is already running. Wait for it to finish.");
            return;
        }

        final boolean upload = hasFlag(args, "upload");
        final String[] pos = positional(args);
        int seconds = 60;

        if (pos.length >= 3) {
            final Integer asked = seconds(sender, pos[2], 5, 300);

            if (asked == null) return;
            seconds = asked;
        }

        this.plugin.startNettyProfile(player, seconds, sender, upload);
    }

    /** Samples block activity per chunk, so lag can be traced to a place. */
    private void lag(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            this.plugin.cancelLagSample(sender);
            return;
        }

        if (this.plugin.lagSampleRunning()) {
            sep(sender);
            sender.sendMessage(ERR + "A sample is already running. Wait for it, or stop it with /catalyst perf lag cancel.");
            sep(sender);
            return;
        }

        int seconds = 60;

        if (args.length >= 2) {
            final Integer asked = seconds(sender, args[1], 5, 300);

            if (asked == null) return;
            seconds = asked;
        }
        this.plugin.startLagSample(sender, seconds);
    }

    /** /catalyst perf spark [install]: whether spark is here, and installs it if asked. */
    private void spark(CommandSender sender, String[] args) {
        final boolean install = args.length >= 2 && args[1].equalsIgnoreCase("install");
        final String refusal = SparkInstaller.refusal(this.plugin);

        // Answered the same way with or without install: nothing needs downloading.
        if (refusal != null) {
            sep(sender);
            sender.sendMessage(VALUE + refusal);
            sep(sender);
            return;
        }

        if (!install) {
            sep(sender);
            sender.sendMessage(VALUE + "spark is not on this server.");
            this.offerSpark(sender);
            sep(sender);
            return;
        }

        // It downloads and runs code, so it has a permission of its own (ops have it by default).
        if (!sender.hasPermission("catalyst.spark.install")) {
            sep(sender);
            sender.sendMessage(ERR + "Installing spark needs the catalyst.spark.install permission.");
            sep(sender);
            return;
        }
        SparkInstaller.install(this.plugin, sender);
    }

    /** Where spark would come from here, and the command that installs it (filled in, not run). */
    private void offerSpark(CommandSender sender) {
        final String refusal = SparkInstaller.refusal(this.plugin);

        if (refusal != null) {
            sender.sendMessage(MUTED + refusal);
            return;
        }
        sender.sendMessage(MUTED + (PlatformDetector.isFolia()
                ? "Folia switches its bundled spark off, so it needs the Folia build, spark-folia."
                : "Paper 1.21 and later bundle it; this server needs it installed."));
        this.plugin.chatLinks().send(sender, MUTED + "Install " + SparkInstaller.sourceDescription() + ": ",
                WARN + "[/catalyst perf spark install]", "", "/catalyst perf spark install",
                "", ChatLinks.Click.SUGGEST);
    }

    /** spark's live stats, read into a plain-words diagnosis. Copes without spark (most Spigot setups). */
    private void diagnose(CommandSender sender) {
        sep(sender);

        if (!SparkSupport.isAvailable()) {
            sender.sendMessage(ERR + "This needs the spark profiler, which is not on this server.");
            this.offerSpark(sender);
            sender.sendMessage(MUTED + "Meanwhile /catalyst perf tps and /catalyst perf lag still work.");
            sep(sender);
            return;
        }

        sender.sendMessage(MUTED + "Reading spark statistics...");
        this.plugin.runAsync(() -> {
            final gg.catalyst.spark.SparkBridge.Snapshot snap;

            try {
                snap = SparkSupport.send(sender, this.plugin.chatLinks());
            } catch (Throwable t) {
                sender.sendMessage(ERR + "Could not read spark: " + t);
                sep(sender);
                return;
            }

            // No point suggesting the lag sampler when the tick is healthy.
            final boolean tickOverloaded = (snap.hasMspt() && snap.msptMean() >= 40)
                    || snap.tps1m() < 19.0;

            if (tickOverloaded) {
                this.plugin.chatLinks().send(sender, MUTED + "  Find where it is coming from: ", BRAND + "[/catalyst perf lag 30]", "",
                        "/catalyst perf lag 30", "Find which chunks are generating load", ChatLinks.Click.RUN);
            }
            sep(sender);
        });
    }

    private static ChatColor colorForPing(int ping) {
        return Rating.forPing(ping).color;
    }

    private static ChatColor colorForTps(double tps) {
        return Rating.forTps(tps).color;
    }

    private void modules(CommandSender sender) {
        sep(sender);
        sender.sendMessage(BRAND + "Runtime modules " + MUTED + "(active optimizations, not config values)");

        for (final RuntimeModule module : this.plugin.runtime().modules()) {
            this.sendModuleRow(sender, module);
        }

        sender.sendMessage("");
        sender.sendMessage(BRAND + "Protection guards " + MUTED + "(crash exploit mitigations)");

        for (final RuntimeModule guard : this.plugin.protection().guards()) {
            this.sendModuleRow(sender, guard);
        }
        sep(sender);
    }

    private void sendModuleRow(CommandSender sender, RuntimeModule module) {
        final String state = module.isEnabled() ? SAFE + "on " : MUTED + "off";
        final String toggle = "/catalyst modules " + module.id() + (module.isEnabled() ? " off" : " on");
        this.plugin.chatLinks().send(sender, "  " + state + " " + VALUE + module.name() + " ",
                WARN + "[turn " + (module.isEnabled() ? "off" : "on") + "]", "",
                toggle, "", ChatLinks.Click.SUGGEST);
        sender.sendMessage(MUTED + "      " + module.description());

        if (module.isEnabled()) sender.sendMessage(MUTED + "      " + module.status());
    }

    /** Turns one runtime module or protection guard on or off and saves it to config.yml, comments intact. */
    private void toggleModule(CommandSender sender, String[] args) {
        sep(sender);

        // Search runtime modules first, then protection guards.
        RuntimeModule module = this.plugin.runtime().modules().stream()
                .filter(m -> m.id().equalsIgnoreCase(args[0])).findFirst().orElse(null);
        final boolean isProtection = module == null;

        if (isProtection) {
            module = this.plugin.protection().guards().stream()
                    .filter(m -> m.id().equalsIgnoreCase(args[0])).findFirst().orElse(null);
        }

        if (module == null) {
            final String runtimeIds = this.plugin.runtime().modules().stream().map(RuntimeModule::id)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            final String protectionIds = this.plugin.protection().guards().stream().map(RuntimeModule::id)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            sender.sendMessage(ERR + "No module called " + args[0] + ".");
            sender.sendMessage(MUTED + "  Runtime: " + runtimeIds);
            sender.sendMessage(MUTED + "  Protection: " + protectionIds);
            sep(sender);
            return;
        }

        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage(WARN + "Usage: /catalyst modules " + module.id() + " <on|off>");
            sep(sender);
            return;
        }

        final boolean on = args[1].equalsIgnoreCase("on");

        if (module.isEnabled() == on) {
            sender.sendMessage(MUTED + module.name() + " is already " + (on ? "on" : "off") + ".");
            sep(sender);
            return;
        }

        final String configPrefix = isProtection ? "protection" : "runtime";

        try {
            ConfigWriter.set(new File(this.plugin.getDataFolder(), "config.yml"),
                    configPrefix + "." + module.id() + ".enabled", on);
        } catch (IOException e) {
            sender.sendMessage(ERR + "Could not save config.yml: " + e.getMessage());
            sep(sender);
            return;
        }
        this.plugin.reloadConfig();
        this.plugin.runtime().reload();
        this.plugin.protection().reload();
        sender.sendMessage((on ? SAFE : WARN) + module.name() + " turned " + (on ? "on" : "off")
                + MUTED + " and saved to config.yml.");
        sep(sender);
    }

    private void backups(CommandSender sender, String label) {
        sep(sender);
        final List<File> list = this.plugin.engine().backups().listBackups();

        if (list.isEmpty()) {
            sender.sendMessage(MUTED + "No backups yet. One is created whenever you apply changes.");
            sep(sender);
            return;
        }
        sender.sendMessage(BRAND + "Backups " + MUTED + "(newest first)");

        for (int i = 0; i < list.size(); i++)
            this.plugin.chatLinks().send(sender, MUTED + "  " + (i + 1) + ". ", VALUE + list.get(i).getName(), MUTED + "  [restore]",
                    "/catalyst config restore " + (i + 1), "", ChatLinks.Click.SUGGEST);
        sender.sendMessage(MUTED + "Restore one with /" + label + " restore <number>");
        sep(sender);
    }

    private void restore(CommandSender sender, String[] args, String label) {
        if (this.busy(sender)) return;
        sep(sender);
        final List<File> list = this.plugin.engine().backups().listBackups();

        if (list.isEmpty()) {
            sender.sendMessage(MUTED + "No backups to restore from.");
            sep(sender);
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ERR + "Specify which backup. See /" + label + " backups");
            sep(sender);
            return;
        }

        int index;

        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ERR + "That is not a number. See /" + label + " backups");
            sep(sender);
            return;
        }

        if (index < 1 || index > list.size()) {
            sender.sendMessage(ERR + "No backup number " + index + ". See /" + label + " backups");
            sep(sender);
            return;
        }

        final File backup = list.get(index - 1);
        this.plugin.runAsync(() -> {
            try {
                this.plugin.engine().backups().restore(backup, PlatformDetector.serverRoot());
                sender.sendMessage(SAFE + "Restored config files from " + backup.getName() + ".");
                sender.sendMessage(WARN + "Restart the server for the restored settings to take effect.");
            } catch (Exception e) {
                sender.sendMessage(ERR + "Restore failed: " + e.getMessage());
            }
            sep(sender);
        });
    }

    private void status(CommandSender sender) {
        sep(sender);
        final var platform = this.plugin.engine().platform();
        sender.sendMessage(BRAND + Branding.name() + " status");
        sender.sendMessage(MUTED + "  Platform: " + VALUE + platform.displayName);
        sender.sendMessage(MUTED + "  spigot.yml: " + VALUE + yesNo(platform.hasSpigotConfig)
                + MUTED + "  Paper config: " + VALUE + yesNo(platform.hasPaperConfig)
                + MUTED + "  Folia: " + VALUE + yesNo(platform.isFolia));
        sender.sendMessage(MUTED + "  Runtime modules active: " + VALUE + this.plugin.runtime().enabledCount()
                + MUTED + " of " + VALUE + this.plugin.runtime().modules().size()
                + MUTED + "  Protection guards: " + VALUE + this.plugin.protection().enabledCount()
                + MUTED + " of " + VALUE + this.plugin.protection().guards().size());
        sender.sendMessage(MUTED + "  Upload module: " + VALUE + this.uploadState());
        sender.sendMessage(MUTED + "  TPS watchdog: " + VALUE + this.plugin.tpsWatchdog().status());
        this.sendMemory(sender);

        // Coloured against the same targets the optimizations use.
        for (final World world : this.plugin.getServer().getWorlds()) {
            final String view = (world.getViewDistance() <= 8 ? SAFE : WARN) + world.getViewDistance();
            final String sim = (world.getSimulationDistance() <= 4 ? SAFE : WARN) + world.getSimulationDistance();
            final String mobs = (liveMonsterLimit(world) <= 50 ? SAFE : WARN) + liveMonsterLimit(world);
            sender.sendMessage(MUTED + "  " + world.getName() + " live: "
                    + MUTED + "view=" + view
                    + MUTED + " sim=" + sim
                    + MUTED + " monsters=" + mobs);
        }

        final ScanResult last = this.plugin.lastScan();

        if (last == null) {
            sender.sendMessage(MUTED + "  No scan run yet this session. Try /catalyst config check");
        } else {
            sender.sendMessage(MUTED + "  Last scan: " + VALUE + last.findings().size()
                    + MUTED + " suggestion(s), " + VALUE + last.passing().size() + MUTED + " already optimal");
        }
        sep(sender);
    }

    /** Per-type getter kept for 1.16 compatibility; see SpawnLimitsOpt. */
    @SuppressWarnings("deprecation")
    private static int liveMonsterLimit(World world) {
        return world.getMonsterSpawnLimit();
    }

    private String uploadState() {
        if (!this.plugin.uploader().isEnabled()) return "disabled";

        return this.plugin.uploader().isConfigured() ? "enabled" : "enabled but not configured";
    }

    /** Refuses a config change or reload while a run is going, since it would disrupt it. */
    private boolean busy(CommandSender sender) {
        final String active = this.plugin.activeOperation();

        if (active == null) return false;

        sep(sender);
        sender.sendMessage(ERR + "Hold on - " + active + ", and this would disrupt it. Wait for it, or cancel it first.");
        sep(sender);
        return true;
    }

    private void reload(CommandSender sender) {
        if (this.busy(sender)) return;
        sep(sender);
        this.plugin.reloadConfig();
        this.plugin.runtime().reload();
        this.plugin.protection().reload();
        this.plugin.uploader().configure(this.plugin.getConfig(), this.plugin.getLogger());
        this.plugin.tpsWatchdog().configure(this.plugin.getConfig());
        sender.sendMessage(SAFE + Branding.name() + " config reloaded. "
                + this.plugin.runtime().enabledCount() + " runtime module(s), "
                + this.plugin.protection().enabledCount() + " protection guard(s) active.");
        final String libraries = this.plugin.reloadLibraries();

        if (libraries != null) sender.sendMessage(MUTED + libraries);
        sep(sender);
    }

    /** Read-only entries run on click; anything that changes the server only fills the chat bar. */
    private void help(CommandSender sender, String label) {
        sep(sender);
        sender.sendMessage(BRAND + Branding.name() + " " + MUTED + "- server performance optimizer  "
                + (this.plugin.chatLinks().isClickable() ? "(click to open)" : ""));

        this.helpLine(sender, label, "perf", "", "find out why the server is lagging", true);
        this.helpLine(sender, label, "config", "", "check and tune server config files", true);
        this.helpLine(sender, label, "network", "", "latency and packet pipeline", true);
        this.helpLine(sender, label, "modules", " [module on|off]", "runtime limiters, and turn them on or off", true);
        this.helpLine(sender, label, "status", "", "platform, modules and last scan", true);
        this.helpLine(sender, label, "reload", "", "reload config and runtime modules", false);
        sep(sender);
    }

    /** The subcommands inside one group, each clickable like the top level. */
    private void groupHelp(CommandSender sender, String label, String group) {
        switch (group) {
            case "perf" -> {
                sep(sender);
                sender.sendMessage(BRAND + Branding.name() + " perf " + MUTED + "- why is the server lagging?");
                this.helpLine(sender, label, "perf profile", " [seconds]" + this.uploadFlag(), "profile every thread and name the culprits", true);
                this.helpLine(sender, label, "perf plugins", " [seconds]" + this.uploadFlag(), "worst plugins and their worst listeners", true);
                this.helpLine(sender, label, "perf profile cancel", "", "stop a running profile, with no report", true);
                this.helpLine(sender, label, "perf stats", "", "quick read of spark's live numbers", true);
                this.helpLine(sender, label, "perf lag", " [seconds]", "which chunks are generating load", true);
                this.helpLine(sender, label, "perf lag cancel", "", "stop a running lag sample, with no report", true);
                this.helpLine(sender, label, "perf tps", "", "current tick rate and watchdog", true);
                this.helpLine(sender, label, "perf bench", " [low|medium|harsh|extreme] [force]", "measure headroom in a throwaway world", false);
                this.helpLine(sender, label, "perf bench skip", "", "skip the benchmark's current stage", false);
                this.helpLine(sender, label, "perf bench cancel", "", "stop a running benchmark, with no report", false);
                this.helpLine(sender, label, "perf bench bots", " [count]", "quick real-client test, no config needed", false);
                this.helpLine(sender, label, "perf bench bots", " [count] here", "bots roam your world around you", false);
                this.helpLine(sender, label, "perf bench bots", " [count] spread", "bots roam spots scattered around you", false);
                this.helpLine(sender, label, "perf spark install", "", "download and load spark for this platform", false);
                sep(sender);
            }
            case "config" -> {
                sep(sender);
                sender.sendMessage(BRAND + Branding.name() + " config " + MUTED + "- tune server config files");
                this.helpLine(sender, label, "config check", this.uploadFlag(), "scan and explain every suggestion", true);
                this.helpLine(sender, label, "config apply safe", "", "apply the no-downside changes", false);
                this.helpLine(sender, label, "config apply moderate", "", "also apply changes with trade-offs", false);
                this.helpLine(sender, label, "config fix", " <id>", "apply one setting on its own", false);
                this.helpLine(sender, label, "config backups", "", "list saved config backups", true);
                this.helpLine(sender, label, "config restore", " <number>", "roll back to a backup", false);
                sep(sender);
            }
            case "network" -> {
                sep(sender);
                sender.sendMessage(BRAND + Branding.name() + " network " + MUTED + "- latency and packets");
                this.helpLine(sender, label, "network info", "", "player ping and packet settings", true);
                this.helpLine(sender, label, "network pipeline", " [player]", "what plugins injected into the pipeline", true);
                this.helpLine(sender, label, "network profile", " [seconds]" + this.uploadFlag(), "time each handler on your connection", true);
                this.helpLine(sender, label, "network tps", " [seconds]", "how late the network threads are running", true);
                sep(sender);
            }
            default -> this.help(sender, label);
        }
    }

    private void helpLine(CommandSender sender, String label, String sub, String args,
                          String description, boolean readOnly) {
        final String command = "/" + label + " " + sub;

        // Required arguments can't be guessed, so those are always suggested.
        final boolean runnable = readOnly && !args.contains("<");
        this.plugin.chatLinks().send(sender,
                MUTED + "  ",
                (runnable ? BRAND : WARN) + command + MUTED + args,
                VALUE + "  " + description,
                command + (args.isEmpty() || args.contains("[") ? "" : " "),
                "", // the tooltip already says "Click runs:" / "Click puts this in your chat bar:" itself
                runnable ? ChatLinks.Click.RUN : ChatLinks.Click.SUGGEST);
    }


    /** Heap use against -Xmx - the figure behind a bot memory refusal. */
    private void sendMemory(CommandSender sender) {
        final Runtime rt = Runtime.getRuntime();
        final long max = rt.maxMemory();
        final long used = rt.totalMemory() - rt.freeMemory();
        final long usedMib = used / (1024 * 1024), maxMib = max / (1024 * 1024);
        final int percent = (int) (100 * used / Math.max(1, max));
        final String colour = percent < 75 ? SAFE : percent < 90 ? WARN : ERR;
        sender.sendMessage(MUTED + "  Heap: " + colour + usedMib + "MB" + MUTED + " of "
                + VALUE + maxMib + "MB " + MUTED + "-Xmx " + colour + "(" + percent + "%)");
    }

    private static String yesNo(boolean b) { return b ? "yes" : "no"; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return matching(args[0], "perf", "config", "network", "modules", "status", "reload");

        final String group = args[0].toLowerCase();

        if (args.length == 2) {
            return switch (group) {
                case "perf" -> matching(args[1], "profile", "plugins", "stats", "lag", "tps", "bench", "spark");
                case "config" -> matching(args[1], "check", "apply", "fix", "backups", "restore");
                case "network" -> matching(args[1], "info", "pipeline", "profile", "tps");
                case "modules" -> {
                    final List<String> allIds = new ArrayList<>();
                    this.plugin.runtime().modules().forEach(m -> allIds.add(m.id()));
                    this.plugin.protection().guards().forEach(m -> allIds.add(m.id()));
                    yield matching(args[1], allIds.toArray(String[]::new));
                }
                default -> List.of();
            };
        }
        final String sub = args[1].toLowerCase();

        // Flags can go anywhere after the subcommand, including after a number of seconds.
        if (takesUpload(group, sub)) {
            if (group.equals("perf") && args.length > 3 && args[2].equalsIgnoreCase("cancel")) return List.of();

            // cancel is only offered first, and while there is something to cancel.
            if (group.equals("perf") && args.length == 3 && this.plugin.sparkProfileRunning())
                return matching(args[2], "cancel");
            if (group.equals("network") && sub.equals("profile") && args.length == 3 && this.plugin.nettyProfileRunning())
                return matching(args[2], "cancel");

            final String typed = args[args.length - 1];
            final boolean uploadGiven   = Arrays.stream(args, 2, args.length - 1).anyMatch(a -> a.equalsIgnoreCase("-upload"));
            final boolean uploadAvail   = this.plugin.uploadReady() && !uploadGiven;
            final boolean secsGiven     = Arrays.stream(args, 2, args.length - 1).anyMatch(a -> a.matches("\\d+"));
            // config check takes no seconds argument; every other takesUpload command does.
            final boolean takesSecs     = !group.equals("config");

            final List<String> opts = new ArrayList<>();
            if (takesSecs && !secsGiven) opts.add("60");
            if (uploadAvail) opts.add("-upload");
            return matching(typed, opts.toArray(String[]::new));
        }

        // While a bench runs, the only useful words are stopping or skipping the current stage.
        if (group.equals("perf") && sub.equals("bench") && this.plugin.benchmarkRunning() && args.length == 3)
            return matching(args[2], "cancel", "skip");

        // Each bench word only where it comes next; force goes last.
        if (group.equals("perf") && sub.equals("bench") && !this.plugin.benchmarkRunning()) {
            final List<String> used = Arrays.stream(args, 2, args.length - 1).map(String::toLowerCase).toList();
            final String typed = args[args.length - 1];

            if (used.isEmpty()) return matching(typed, "bots", "low", "medium", "harsh", "extreme");

            if (used.contains("force")) return List.of();

            if (used.get(used.size() - 1).equals("bots"))
                return matching(typed, String.valueOf(this.plugin.getConfig().getInt("benchmark.bots.count", 20)), "here", "spread");

            // After bots and a count: here or spread, then force.
            if (used.contains("bots") && !used.contains("here") && !used.contains("spread")
                    && used.get(used.size() - 1).matches("\\d{1,3}"))
                return matching(typed, "here", "spread", "force");
            return matching(typed, "force");
        }

        // config fix takes a list of optimization ids; kept out of config apply, which is levels only.
        // Only offer settings the last scan still recommends - never one that is already set that
        // way - and drop any the player has already typed on this line.
        if (group.equals("config") && sub.equals("fix")) {
            final Set<String> already = new HashSet<>(Arrays.asList(args).subList(2, args.length - 1));
            final List<String> offer = new ArrayList<>();

            for (final String id : this.plugin.pendingOptimizationIds())
                if (!already.contains(id)) offer.add(id);
            return matching(args[args.length - 1], offer.toArray(new String[0]));
        }

        if (args.length == 3) {
            if (group.equals("config") && sub.equals("apply"))
                return matching(args[2], this.levelNames().toArray(new String[0]));

            // config restore takes a backup number (newest first); offer the ones that exist.
            if (group.equals("config") && sub.equals("restore")) {
                final int count = this.plugin.engine().backups().listBackups().size();
                final List<String> numbers = new ArrayList<>();

                for (int i = 1; i <= count; i++) numbers.add(String.valueOf(i));
                return matching(args[2], numbers.toArray(new String[0]));
            }

            if (group.equals("perf") && sub.equals("spark"))
                return matching(args[2], "install");

            if (group.equals("perf") && sub.equals("lag"))
                return this.plugin.lagSampleRunning() ? matching(args[2], "cancel") : matching(args[2], "30");

            if (group.equals("network") && sub.equals("tps") && this.loopSampleRunning)
                return matching(args[2], "cancel");

            if (group.equals("modules"))
                return matching(args[2], "on", "off");

            if (group.equals("perf") && sub.equals("bench") && this.plugin.benchmarkRunning())
                return matching(args[2], "cancel");

            if (group.equals("network") && sub.equals("pipeline")) {
                final List<String> names = new ArrayList<>();

                for (final Player p : this.plugin.getServer().getOnlinePlayers()) names.add(p.getName());
                return matching(args[2], names.toArray(new String[0]));
            }
        }

        return List.of();
    }

    /** The -upload flag for help lines, shown only when upload is set up. */
    private String uploadFlag() {
        return this.plugin.uploadReady() ? " [-upload]" : "";
    }

    private static boolean takesUpload(String group, String sub) {
        return (group.equals("config") && sub.equals("check"))
            || (group.equals("perf") && (sub.equals("profile") || sub.equals("plugins")))
            || (group.equals("network") && sub.equals("profile"));
    }

    private static List<String> matching(String typed, String... options) {
        final List<String> out = new ArrayList<>();

        for (final String option : options)
            if (option.toLowerCase().startsWith(typed.toLowerCase())) out.add(option);

        return out;
    }
}

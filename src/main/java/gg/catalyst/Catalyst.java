// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst;

import gg.catalyst.bench.Benchmark;
import gg.catalyst.bench.bots.BotStage;
import gg.catalyst.chat.ChatLinks;
import gg.catalyst.command.CatalystCommand;
import gg.catalyst.event.CatalystOptimizationsAppliedEvent;
import gg.catalyst.integrity.JarIntegrityChecker;
import gg.catalyst.lag.ChartRenderer;
import gg.catalyst.lag.Hotspot;
import gg.catalyst.lag.LagSampler;
import gg.catalyst.libs.LibraryDownloader;
import gg.catalyst.libs.LibraryManifest;
import gg.catalyst.netty.NettyInspector;
import gg.catalyst.netty.NettyProfiler;
import gg.catalyst.netty.ProfileSession;
import gg.catalyst.optimization.*;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.platform.SchedulerAdapter;
import gg.catalyst.protection.ProtectionManager;
import gg.catalyst.report.Rating;
import gg.catalyst.report.ReportRenderer;
import gg.catalyst.runtime.RuntimeManager;
import gg.catalyst.runtime.RuntimeModule;
import gg.catalyst.spark.*;
import gg.catalyst.tps.FoliaTicks;
import gg.catalyst.tps.RegionReport;
import gg.catalyst.tps.TpsSource;
import gg.catalyst.tps.TpsWatchdog;
import gg.catalyst.upload.ReportUploader;
import gg.catalyst.upload.UploadResult;
import gg.catalyst.util.Branding;
import gg.catalyst.util.ConfigWriter;
import gg.catalyst.util.JsonUtil;
import gg.catalyst.util.PluginInfo;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.logging.Level;

@SuppressWarnings("deprecation") // ChatColor: intentional per code style; getDescription(): Paper-only replacement
public final class Catalyst extends JavaPlugin {
    /** Catalyst's page on bstats.org. */
    private static final int BSTATS_ID = 34291;

    private CatalystEngine engine;
    private SchedulerAdapter scheduler;
    private RuntimeManager runtime;
    private ProtectionManager protection;
    private ReportUploader uploader;
    private TpsSource tpsSource;
    private TpsWatchdog tpsWatchdog;
    private ScanResult lastScan;
    private LibraryDownloader libraries;

    private static void sep(CommandSender s) { s.sendMessage(Branding.SEP); }

    private static String stripColors(String s) { return s.replaceAll("§[0-9a-fk-orA-FK-OR]", ""); }

    /** True when an upload would actually be attempted, so the report can skip chat. */
    public boolean uploadReady() { return this.uploader.isEnabled() && this.uploader.isConfigured(); }

    /** Uploads chat lines (colors stripped) and sends back the URL. If upload can't run, the report was already in chat. */
    public void uploadReport(CommandSender sender, List<String> lines, String baseName) {
        if (!this.uploader.isEnabled()) {
            sender.sendMessage(ChatColor.YELLOW + "Upload flag ignored: upload is disabled. Set experimental.upload.enabled: true in config.yml.");
            return;
        }

        if (!this.uploader.isConfigured()) {
            sender.sendMessage(ChatColor.YELLOW + "Upload flag ignored: no endpoint set. Add experimental.upload.request.endpoint to config.yml.");
            return;
        }
        sep(sender);
        sender.sendMessage(ChatColor.GRAY + "Uploading report...");
        this.runAsync(() -> {
            final String name = baseName + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".txt";
            final StringBuilder body = new StringBuilder();

            for (final String line : lines) body.append(stripColors(line)).append(System.lineSeparator());
            final UploadResult result = this.uploader.upload(name, body.toString());

            if (!result.success()) {
                sender.sendMessage(ChatColor.RED + "Upload failed: " + result.message());
                sender.sendMessage(ChatColor.GRAY + "Set experimental.upload.debug: true for the full error in the console.");
            } else if (result.urls().isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "Upload sent but the server returned no URL. Check the response-url-path in config.yml.");
            } else {
                for (final String url : result.urls())
                    this.chatLinks.send(sender, ChatColor.GRAY + "Uploaded: ", "§b" + url, "", url, "Open the report in your browser", ChatLinks.Click.OPEN_URL);
            }
            sep(sender);
        });
    }

    @Override
    public void onEnable() {
        Branding.set(super.getName());

        // A jar changed since it was built is most likely malware.
        if (super.getConfig().getBoolean("security.verify-jar-integrity", true)
                && !new JarIntegrityChecker(super.getLogger(), Branding.name()).verifyCurrentJar()) {
            super.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        super.saveDefaultConfig();
        this.addNewConfigOptions();
        this.updateBenchStages();

        Benchmark.deleteLeftover();
        BotStage.removeLeftovers(this);
        BotStage.cleanWorkFiles(this, true);

        this.engine = new CatalystEngine(this);
        this.scheduler = SchedulerAdapter.create();
        this.runtime = new RuntimeManager(this);
        this.protection = new ProtectionManager(this);
        this.uploader = new ReportUploader();
        this.tpsSource = TpsSource.create(this);
        this.tpsWatchdog = new TpsWatchdog(this, this.tpsSource);

        this.runtime.start();
        this.protection.start();
        this.uploader.configure(super.getConfig(), super.getLogger());
        JsonUtil.setup(this);
        this.tpsWatchdog.configure(super.getConfig());
        this.tpsWatchdog.start();

        final PluginCommand cmd = super.getCommand("catalyst");

        if (cmd != null) {
            final CatalystCommand handler = new CatalystCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        super.getLogger().info("Running on " + this.engine.platform().displayName
                + ", " + this.runtime.enabledCount() + " runtime module(s), "
                + this.protection.enabledCount() + " protection guard(s) active.");

        this.startLibraryDownload();
        this.startMetrics();

        if (super.getConfig().getBoolean("scan-on-startup", true)) {
            final long delay = super.getConfig().getLong("startup-delay-seconds", 10);
            this.scheduler.runDelayedAsync(this, this::startupScan, delay);
        }
    }

    /**
     * For /catalyst reload: restarts the download if it's wanted and not running/ready, or if
     * libraries.via changed. Returns a line for the reload message, or null if nothing changed.
     */
    public String reloadLibraries() {
        if (!super.getConfig().getBoolean("libraries.download", true)) return null;
        final boolean latest = "latest".equalsIgnoreCase(super.getConfig().getString("libraries.via", "pinned"));

        if (this.libraries != null && this.libraries.state() == LibraryDownloader.State.DOWNLOADING) return null;

        if (this.libraries != null && this.libraries.isReady() && this.libraries.followsLatestVia() == latest) return null;

        // A running bot process has these jars open; replacing them under it would break it.
        if (this.benchmarkRunning()) return "Libraries will be fetched again after the benchmark: run /catalyst reload then.";

        this.startLibraryDownload();

        return "Libraries are being fetched and checked again in the background.";
    }

    /** Only the bot stage needs these, so they load in the background and never hold up startup. */
    @SuppressWarnings("deprecation") // getDescription(): its replacement is Paper-only
    private void startLibraryDownload() {
        this.libraries = new LibraryDownloader(new File(super.getDataFolder(), "libs"), LibraryManifest.ALL,
                Branding.name() + "/" + super.getDescription().getVersion(),
                "latest".equalsIgnoreCase(super.getConfig().getString("libraries.via", "pinned")), super.getLogger());

        if (!super.getConfig().getBoolean("libraries.download", true)) {
            this.libraries.disable();
            return;
        }
        this.scheduler.runAsync(this, this.guarded("library download", () -> {
            final String summary = this.libraries.run();

            if (this.libraries.isReady()) super.getLogger().info("Libraries ready: " + summary);
            else super.getLogger().warning("Libraries unavailable, bot benchmark disabled: " + summary);
        }));
    }

    private void startMetrics() {
        if (!super.getConfig().getBoolean("metrics", true))
            return;

        final Metrics metrics = new Metrics(this, BSTATS_ID);

        metrics.addCustomChart(new SimplePie("server_platform", () -> this.engine.platform().displayName));
        metrics.addCustomChart(new SimplePie("benchmark_bots",
                () -> super.getConfig().getBoolean("benchmark.bots.enabled", false) ? "Enabled" : "Disabled"));
        metrics.addCustomChart(new AdvancedPie("runtime_modules", () -> {
            final Map<String, Integer> enabled = new HashMap<>();

            for (final RuntimeModule module : this.runtime.modules())
                if (module.isEnabled())
                    enabled.put(module.name(), 1);

            if (enabled.isEmpty())
                enabled.put("None", 1);

            return enabled;
        }));
        metrics.addCustomChart(new SimplePie("spark", () -> {
            if (super.getServer().getPluginManager().getPlugin("spark") != null)
                return "Installed";

            return SparkSupport.isAvailable() ? "Bundled" : "None";
        }));
        metrics.addCustomChart(new SimplePie("libraries_via",
                () -> "latest".equalsIgnoreCase(super.getConfig().getString("libraries.via", "pinned")) ? "Latest" : "Pinned"));
        metrics.addCustomChart(new SimplePie("report_upload", () -> this.uploader.isEnabled() ? "Enabled" : "Disabled"));
        metrics.addCustomChart(new SimplePie("tps_watchdog", () -> this.tpsWatchdog.isEnabled() ? "Enabled" : "Disabled"));

        // How much tuning is still suggested, from the last scan; bucketed so it stays a rough signal.
        metrics.addCustomChart(new SimplePie("pending_config_changes", () -> {
            final ScanResult scan = this.lastScan;

            if (scan == null)
                return "No scan yet";

            final int pending = scan.findings().size();

            if (pending == 0)
                return "0";

            return pending <= 3 ? "1-3" : pending <= 7 ? "4-7" : "8+";
        }));
        metrics.addCustomChart(new SingleLineChart("benchmarks_run", () -> this.benchmarksRun.getAndSet(0)));
    }

    @Override
    public void onDisable() {
        if (this.lagSampler.isActive()) this.lagSampler.stop();

        if (this.activeBench != null && (!this.activeBench.isFinished() || this.activeBench.isClosing())) this.activeBench.cleanup();

        // spark stops only when told to, so a profile of Catalyst's must not outlive it.
        final int sparkRun = this.sparkProfileRun.getAndSet(0);

        if (sparkRun != 0) {
            try {
                this.stopSparkEarly(sparkRun);
            } catch (Throwable ignored) {
                // spark went down first; its profiler went with it.
            }
        }

        // Probes left in a pipeline after unload would reference classes that no longer exist.
        if (this.activeProfile != null) {
            this.activeProfile.stop();
            this.activeProfile = null;
        }
    }

    /** Merges options a newer version added into the existing config.yml, keeping what is already set. */
    private void addNewConfigOptions() {
        try {
            final List<String> added = ConfigWriter.addMissingDefaults(new File(super.getDataFolder(), "config.yml"),
                    super.getResource("config.yml"));

            if (!added.isEmpty()) {
                super.reloadConfig();
                super.getLogger().info("Added " + added.size() + " new option" + (added.size() == 1 ? "" : "s")
                        + " to config.yml: " + String.join(", ", added));
            }
        } catch (Exception e) {
            super.getLogger().warning("Could not add new options to config.yml: " + e.getMessage());
        }
    }

    /** Pre-levels configs have one number per stage; old defaults are removed so the levels apply. */
    private void updateBenchStages() {
        try {
            final ConfigWriter.StageUpdate update = ConfigWriter.updateBenchStages(new File(super.getDataFolder(), "config.yml"));

            if (!update.updated().isEmpty()) {
                super.reloadConfig();
                super.getLogger().info("Updated config.yml: " + String.join(", ", update.updated())
                        + " now follow the benchmark levels (LOW, MEDIUM, HARSH, EXTREME) instead of one fixed amount.");
            }

            if (!update.fixed().isEmpty())
                super.getLogger().info("benchmark.stages " + String.join(", ", update.fixed()) + (update.fixed().size() == 1 ? " is" : " are")
                        + " set to a single number, so the same amount is used at every level. Give "
                        + (update.fixed().size() == 1 ? "it" : "each") + " LOW, MEDIUM, HARSH and EXTREME amounts to use the levels.");
        } catch (Exception e) {
            super.getLogger().warning("Could not update the benchmark stages in config.yml: " + e.getMessage());
        }
    }

    private void startupScan() {
        final ScanResult scan = this.engine.scan();
        this.lastScan = scan;
        final boolean showPassing = super.getConfig().getBoolean("show-passing-checks", false);

        for (final String line : ReportRenderer.summary(scan, showPassing))
            super.getServer().getConsoleSender().sendMessage(line);
    }

    /** Runs a scan off the main thread, then hands the result back via the callback. */
    public void scanAsync(Consumer<ScanResult> callback) {
        this.scheduler.runAsync(this, this.guarded("scan", () -> {
            final ScanResult scan = this.engine.scan();
            this.lastScan = scan;
            callback.accept(scan);
        }));
    }

    /** Async schedulers swallow exceptions, which makes a failed command look like it did nothing. */
    private Runnable guarded(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                super.getLogger().log(Level.SEVERE, Branding.name() + " " + what + " task failed", t);
            }
        };
    }

    /** Applies every pending change up to the level. */
    public void applyAsync(OptimizationLevel level, Consumer<ApplyResult> callback) {
        this.applyAsync(() -> this.engine.apply(level), callback);
    }

    /** Applies only the named optimizations - one recommendation's fix, not a whole level. */
    public void applyOnlyAsync(java.util.Set<String> ids, Consumer<ApplyResult> callback) {
        this.applyAsync(() -> this.engine.applyOnly(ids), callback);
    }

    /** Writes config first so it survives a restart, then pushes what the running server accepts live. */
    private void applyAsync(java.util.function.Supplier<ApplyResult> apply, Consumer<ApplyResult> callback) {
        this.scheduler.runAsync(this, this.guarded("apply", () -> {
            final ApplyResult result = apply.get();

            // The config files were just rewritten, so re-scan to keep the cached pending set
            // honest: what was applied stops being offered (e.g. by config fix tab-completion).
            this.lastScan = this.engine.scan();

            final List<Optimization> live = result.applied().stream()
                    .filter(Optimization::canApplyLive)
                    .toList();

            if (live.isEmpty()) {
                // Nothing to push live, but the event still fires - on the server thread, not here.
                this.runGlobal(() -> this.fireOptimizationsApplied(result));
                callback.accept(result);
                return;
            }

            this.scheduler.runGlobal(this, this.guarded("live apply", () -> {
                for (final Optimization opt : live) {
                    try {
                        opt.applyLive(super.getServer());
                        result.addLiveApplied(opt);
                    } catch (Throwable t) {
                        // Older servers may not expose the setter; the config write still stands.
                        result.addError(opt.name() + " could not be applied live: " + t);
                    }
                }
                this.fireOptimizationsApplied(result);
                callback.accept(result);
            }));
        }));
    }

    /** Tells other plugins what changed. Server thread only. */
    private void fireOptimizationsApplied(ApplyResult result) {
        if (result.applied().isEmpty()) return;

        try {
            super.getServer().getPluginManager().callEvent(new CatalystOptimizationsAppliedEvent(
                    result.applied(), result.liveApplied(), result.backupDir()));
        } catch (Throwable t) {
            super.getLogger().warning("Applied-optimizations event failed: " + t);
        }
    }

    // ── Packet profiling ──────────────────────────────────────────────────
    // Held here, not in the command, so onDisable can always pull the probes out.

    private volatile NettyProfiler activeProfile;

    // So a cancelled profile's pending finish can't report after it was stopped.
    private final AtomicInteger nettyRuns = new AtomicInteger();

    public boolean nettyProfileRunning() {
        return this.activeProfile != null && !this.activeProfile.isStopped();
    }

    public void startNettyProfile(Player player, int seconds, CommandSender sender, boolean upload) {
        if (this.benchmarkBlocksTools(sender)) return;
        final int run = this.nettyRuns.incrementAndGet();
        this.runGlobal(() -> {
            try {
                this.activeProfile = NettyProfiler.start(player);
            } catch (NettyInspector.Unavailable e) {
                sep(sender);
                sender.sendMessage(ChatColor.RED + "Cannot profile: " + e.getMessage());
                sep(sender);
                return;
            }

            sep(sender);
            sender.sendMessage(ChatColor.AQUA + "Profiling your connection for " + seconds + "s.");
            sender.sendMessage(ChatColor.GRAY + "Move around and play normally so there are packets to measure.");
            this.chatLinks.send(sender, ChatColor.GRAY + "Changed your mind? ", ChatColor.YELLOW + "[/catalyst network profile cancel]", "",
                    "/catalyst network profile cancel", "Stop the profile without a report", ChatLinks.Click.RUN);
            sep(sender);

            this.runDelayed(() -> {
                if (this.nettyRuns.get() == run) this.finishNettyProfile(sender, upload);
            }, seconds);
        });
    }

    /** Stops the packet profile at once and drops it, with no report. */
    public void cancelNettyProfile(CommandSender sender) {
        sep(sender);

        if (!this.nettyProfileRunning()) {
            sender.sendMessage(ChatColor.GRAY + "No packet profile is running.");
        } else {
            this.nettyRuns.incrementAndGet();
            this.runGlobal(() -> {
                final NettyProfiler profiler = this.activeProfile;

                if (profiler != null) {
                    profiler.stop();
                    this.activeProfile = null;
                }
            });
            sender.sendMessage(ChatColor.AQUA + "Packet profile cancelled." + ChatColor.GRAY + " Nothing will be reported for it.");
        }
        sep(sender);
    }

    private void finishNettyProfile(CommandSender sender, boolean upload) {
        final NettyProfiler profiler = this.activeProfile;

        if (profiler == null) return;

        final ProfileSession session = profiler.session();
        profiler.stop();
        this.activeProfile = null;

        final List<ProfileSession.Entry> results = session.results();

        if (results.isEmpty()) {
            sep(sender);
            sender.sendMessage(ChatColor.GRAY + "No packets were measured. Was the connection idle?");
            sep(sender);
            return;
        }

        // With a working upload the report goes only to the file, and chat gets just the link.
        final boolean echo = !(upload && this.uploadReady());
        final List<String> lines = new ArrayList<>();
        final Consumer<String> say = line -> {
            lines.add(line);

            if (echo) sender.sendMessage(line);
        };

        if (echo) sep(sender);
        say.accept(ChatColor.AQUA + "Packet handler report " + ChatColor.GRAY + "(slowest average first)");

        for (final ProfileSession.Entry entry : results) {
            final Rating rating = Rating.forHandlerMillis(entry.averageMillis());
            final String timing = String.format(ChatColor.GRAY + "  %-26s %s%7.3fms " + ChatColor.GRAY + "avg over %,d  ",
                    entry.handler(), rating.color, entry.averageMillis(), entry.samples());

            if (entry.plugin() == null) {
                say.accept(timing + ChatColor.DARK_GRAY + "server");
                continue;
            }
            final String owner = ChatColor.WHITE + "[" + entry.plugin() + "]";
            lines.add(timing + owner);

            if (echo) this.chatLinks.hover(sender, timing, owner, "", PluginInfo.hover(entry.plugin()));
        }
        say.accept(ChatColor.DARK_GRAY + "  green under 0.10ms | yellow under 0.50ms | red above");
        say.accept(ChatColor.GRAY + "Handlers run on the network thread, so the slowest one above");
        say.accept(ChatColor.GRAY + "delays every other packet on this connection.");

        // Timing comes from gaps between probes; a handler that drops a packet pins its gap on a neighbour.
        say.accept(ChatColor.DARK_GRAY + "Figures are indicative: use them to find the suspect, not to bill it.");

        if (echo) sep(sender);

        if (upload) this.uploadReport(sender, lines, "catalyst-network-profile");
    }

    // ── Chunk activity sampling ───────────────────────────────────────────

    private final LagSampler lagSampler = new LagSampler();
    private final ChatLinks chatLinks = ChatLinks.create();

    public ChatLinks chatLinks() { return this.chatLinks; }

    public boolean lagSampleRunning() { return this.lagSampler.isActive(); }

    // So a cancelled sample's pending finish can't end a newer one early.
    private final AtomicInteger lagRuns = new AtomicInteger();

    public void startLagSample(CommandSender sender, int seconds) {
        if (this.benchmarkBlocksTools(sender)) return;
        final int run = this.lagRuns.incrementAndGet();
        this.runGlobal(() -> {
            this.lagSampler.start(this);
            sep(sender);
            sender.sendMessage(ChatColor.AQUA + "Sampling chunk activity for " + seconds + "s.");
            sender.sendMessage(ChatColor.GRAY + "Counting block physics, liquid flow and redstone per chunk.");
            this.chatLinks.send(sender, ChatColor.GRAY + "Changed your mind? ", ChatColor.YELLOW + "[/catalyst perf lag cancel]", "",
                    "/catalyst perf lag cancel", "Stop the sample without a report", ChatLinks.Click.RUN);
            sep(sender);
            this.runDelayed(() -> {
                if (this.lagRuns.get() == run) this.finishLagSample(sender);
            }, seconds);
        });
    }

    /** Stops the chunk activity sample at once and drops it, with no report. */
    public void cancelLagSample(CommandSender sender) {
        sep(sender);

        if (!this.lagSampler.isActive()) {
            sender.sendMessage(ChatColor.GRAY + "No lag sample is running.");
        } else {
            this.lagRuns.incrementAndGet();
            this.runGlobal(this.lagSampler::stop);
            sender.sendMessage(ChatColor.AQUA + "Lag sample cancelled." + ChatColor.GRAY + " Nothing will be reported for it.");
        }
        sep(sender);
    }

    private void finishLagSample(CommandSender sender) {
        final long seconds = this.lagSampler.elapsedSeconds();
        this.runGlobal(lagSampler::stop);

        final List<Hotspot> hotspots = this.lagSampler.results();

        if (hotspots.isEmpty()) {
            sep(sender);
            sender.sendMessage(ChatColor.GRAY + "No block activity was recorded. The world was quiet.");
            sep(sender);
            return;
        }

        sep(sender);
        sender.sendMessage(ChatColor.AQUA + "Busiest chunks " + ChatColor.GRAY + "(over " + seconds + "s)");
        final int shown = Math.min(10, hotspots.size());

        for (int i = 0; i < shown; i++) {
            final Hotspot spot = hotspots.get(i);
            final double perSecond = spot.total() / (double) seconds;
            final Rating rating = Rating.forChunkEventsPerSecond(perSecond);

            sender.sendMessage(String.format(
                    "%s  %,d " + ChatColor.GRAY + "events  %s%.0f/sec  " + ChatColor.DARK_GRAY + "mostly %s",
                    rating.color, spot.total(), rating.color, perSecond, spot.dominantCause()));
            sender.sendMessage(String.format(
                    ChatColor.GRAY + "     chunk " + ChatColor.WHITE + "%d, %d " + ChatColor.GRAY + "in %s   " + ChatColor.DARK_GRAY + "physics %,d / fluid %,d / redstone %,d",
                    spot.chunkX(), spot.chunkZ(), spot.worldName(),
                    spot.physics(), spot.fluid(), spot.redstone()));
            final String tp = "/tp " + spot.blockX() + " ~ " + spot.blockZ();
            this.chatLinks.send(sender, ChatColor.DARK_GRAY + "     ", ChatColor.AQUA + "[" + tp + "]", "",
                    tp, "Teleport to the middle of this chunk", ChatLinks.Click.RUN);
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "  green under 50/sec | yellow under 500/sec | red above");

        if (hotspots.size() > shown)
            sender.sendMessage(ChatColor.DARK_GRAY + "  ...and " + (hotspots.size() - shown) + " more chunks with activity.");
        sep(sender);

        this.writeAndOfferChart(sender, hotspots, seconds);
    }

    /** Renders the chart off the server thread; image encoding is not tick work. */
    private void writeAndOfferChart(CommandSender sender, List<Hotspot> hotspots, long seconds) {
        this.runAsync(() -> {
            final String stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
            final File out = new File(super.getDataFolder(), "reports/chunk-activity-" + stamp + ".png");

            try {
                ChartRenderer.render(hotspots, 15, seconds, out);
            } catch (Throwable t) {
                sep(sender);
                sender.sendMessage(ChatColor.GRAY + "Chart could not be drawn: " + t);
                sep(sender);
                return;
            }
            sep(sender);
            sender.sendMessage(ChatColor.GRAY + "Chart saved to " + ChatColor.WHITE + "plugins/" + Branding.name() + "/reports/" + out.getName());

            if (this.uploader.isEnabled() && this.uploader.isConfigured()) {
                final UploadResult result = this.uploader.uploadFile(out.getName(), out, "image/png");

                if (result.success()) {
                    for (final String url : result.urls())
                        this.chatLinks.send(sender, ChatColor.GRAY + "Chart: ", "§b" + url, "", url, "Open the chart in your browser", ChatLinks.Click.OPEN_URL);
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Upload skipped: " + result.message());
                }
            }
            sep(sender);
        });
    }

    // ── Full spark profiling ──────────────────────────────────────────────
    // spark's API only has summary stats, so we run its profiler command and read the saved file.

    // The run in progress, or 0. Late steps from a cancelled or older run see they're stale.
    private final AtomicInteger sparkRuns = new AtomicInteger();
    private final AtomicInteger sparkProfileRun = new AtomicInteger();
    private volatile Consumer<ProfileAnalysis.Result> quietWaiter;

    public boolean sparkProfileRunning() { return this.sparkProfileRun.get() != 0; }

    private int beginSparkRun() {
        final int run = this.sparkRuns.incrementAndGet();
        this.sparkProfileRun.set(run);

        return run;
    }

    private boolean sparkRunStale(int run) {
        return this.sparkProfileRun.get() != run;
    }

    private void endSparkRun(int run) {
        this.sparkProfileRun.compareAndSet(run, 0);
    }

    private void sparkConsole(String command) {
        super.getServer().dispatchCommand(super.getServer().getConsoleSender(), command);
    }

    // Only ever stop our own profile - never Paper's background one or an admin's.
    private volatile int sparkStartedRun;
    // spark won't start a new profile while still saving the last one.
    private volatile long sparkSavingSince;

    /**
     * Starts spark's profiler and stops it ourselves after {@code seconds}. No --timeout on
     * purpose: in spark 1.10 a profile stopped early still fires its pending timeout into the
     * stopped job and logs an NPE. --save-to-file keeps the profile local instead of uploading it.
     */
    private void runSparkProfiler(int run, int seconds, LongConsumer started) {
        this.whenSparkFree(run, 0, () -> {
            this.sparkConsole("spark profiler start --thread *");
            this.sparkStartedRun = run;
            started.accept(savedNoEarlierThan(seconds));
            this.runDelayed(() -> this.runGlobal(() -> {
                if (this.sparkRunStale(run) || this.sparkStartedRun != run) return;
                this.sparkStartedRun = 0;
                this.sparkSavingSince = System.currentTimeMillis();
                this.sparkConsole("spark profiler stop --save-to-file");
            }), seconds);
        });
    }

    /** Waits (15s max - a stop can save nothing if spark was stopped by hand) for the last save. */
    private void whenSparkFree(int run, int waited, Runnable start) {
        this.runGlobal(() -> {
            if (this.sparkRunStale(run)) return;

            if (this.sparkSaving() && waited < 15) {
                this.runDelayed(() -> this.whenSparkFree(run, waited + 1, start), 1);
                return;
            }
            this.sparkSavingSince = 0;
            start.run();
        });
    }

    /** Whether spark is still writing a profile Catalyst stopped early. */
    private boolean sparkSaving() {
        final long since = this.sparkSavingSince;

        return since != 0 && newestProfileSince(this.sparkDataFolder(), since - 2000) == null;
    }

    /**
     * Stops run's profile early. Not with spark's cancel, which in 1.10 can race the sampler and
     * throw RejectedExecutionException; a normal stop is clean, and its file just goes unread.
     */
    private void stopSparkEarly(int run) {
        if (this.sparkStartedRun != run) return;
        this.sparkStartedRun = 0;
        this.sparkSavingSince = System.currentTimeMillis();
        this.sparkConsole("spark profiler stop --save-to-file");
    }

    /** A profile that stops {@code seconds} after now is only saved after this time. */
    private static long savedNoEarlierThan(int seconds) {
        // A little early, for the file system's timestamp resolution.
        return System.currentTimeMillis() + seconds * 1000L - 2000;
    }

    /** Stops the benchmark's profile quietly; {@code answer} is false when the bench itself is cancelling. */
    public void cancelQuietProfile(boolean answer) {
        final int run = this.sparkProfileRun.get();
        final Consumer<ProfileAnalysis.Result> waiter = this.quietWaiter;

        if (waiter == null || run == 0 || !this.sparkProfileRun.compareAndSet(run, 0)) return;
        this.quietWaiter = null;
        this.runGlobal(() -> {
            this.stopSparkEarly(run);

            if (answer) waiter.accept(null);
        });
    }

    /** Drops the running profile. A waiting benchmark is told there's no profile and carries on. */
    public void cancelSparkProfile(CommandSender sender) {
        final int run = this.sparkProfileRun.getAndSet(0);
        sep(sender);

        if (run == 0) {
            sender.sendMessage(ChatColor.GRAY + "No profile is running.");
            sep(sender);
            return;
        }
        final Consumer<ProfileAnalysis.Result> waiter = this.quietWaiter;
        this.quietWaiter = null;
        this.runGlobal(() -> {
            this.stopSparkEarly(run);

            if (waiter != null) waiter.accept(null);
        });
        sender.sendMessage(ChatColor.AQUA + "Profile cancelled." + ChatColor.GRAY + " Nothing will be reported for it.");
        sep(sender);
    }

    public void startSparkProfile(CommandSender sender, int seconds) {
        this.startSparkProfile(sender, seconds, false, false);
    }

    public void startSparkProfile(CommandSender sender, int seconds, boolean pluginsView) {
        this.startSparkProfile(sender, seconds, pluginsView, false);
    }

    /** @param pluginsView show only the plugin/listener breakdown (/catalyst perf plugins). */
    public void startSparkProfile(CommandSender sender, int seconds, boolean pluginsView, boolean upload) {
        // A benchmark drives the same shared spark profiler (it profiles the server with the bots on
        // it), and spark runs one profile at a time. This is a real conflict, not just skewed numbers,
        // so it is refused outright - benchmark.tools-during-run does not open it.
        if (this.benchmarkRunning()) {
            sender.sendMessage(ChatColor.RED + "A benchmark is running and is using the profiler; spark runs one profile at a time."
                    + " Wait for it, or stop it with /catalyst perf bench cancel.");
            return;
        }
        final int run = this.beginSparkRun();
        final File sparkDir = this.sparkDataFolder();

        if (this.sparkSaving())
            sender.sendMessage(ChatColor.GRAY + "spark is still saving the last profile; starting as soon as it has.");

        // Cancel is the same shared profiler either way; the hint just names the command they ran.
        final String cancel = "/catalyst perf " + (pluginsView ? "plugins" : "profile") + " cancel";
        this.runSparkProfiler(run, seconds, savedAfter -> {
            sep(sender);
            sender.sendMessage(ChatColor.AQUA + "Profiling every thread for " + seconds + "s.");
            sender.sendMessage(ChatColor.GRAY + "Keep the server doing what it normally does - the report explains that load.");
            this.chatLinks.send(sender, ChatColor.GRAY + "Changed your mind? ", ChatColor.YELLOW + "[" + cancel + "]", "",
                    cancel, "Stop the profile without a report", ChatLinks.Click.RUN);
            sep(sender);
            this.runDelayed(() -> this.awaitSparkProfile(run, sender, sparkDir, savedAfter, 0, pluginsView, upload), seconds + 3);
        });
    }

    /**
     * Silent profile for the benchmark. False if spark is missing or busy; otherwise {@code done}
     * gets the analysis on the server thread, or null if spark saved nothing.
     */
    public boolean profileQuietly(int seconds, Consumer<ProfileAnalysis.Result> done) {
        if (!SparkSupport.isAvailable() || this.sparkProfileRunning()) return false;
        final int run = this.beginSparkRun();
        this.quietWaiter = done;
        final File sparkDir = this.sparkDataFolder();
        this.runSparkProfiler(run, seconds, savedAfter ->
                this.runDelayed(() -> this.awaitQuietProfile(run, sparkDir, savedAfter, 0, done), seconds + 3));

        return true;
    }

    private void awaitQuietProfile(int run, File sparkDir, long savedAfter, int attempt,
                                   Consumer<ProfileAnalysis.Result> done) {
        // Cancelled: the cancel has already told the waiter.
        if (this.sparkRunStale(run)) return;
        final File found = newestProfileSince(sparkDir, savedAfter);

        if (found == null && attempt < 15) {
            this.runDelayed(() -> this.awaitQuietProfile(run, sparkDir, savedAfter, attempt + 1, done), 2);
            return;
        }

        if (found == null) this.sparkSavingSince = 0; // nothing came, so nothing is still being written
        ProfileAnalysis.Result result = null;

        try {
            if (found != null)
                result = ProfileAnalysis.analyse(SparkProfile.read(found), WorldHotspots.pluginPackages(this));
        } catch (Throwable t) {
            super.getLogger().warning("Could not read the benchmark's spark profile: " + t);
        }

        // Whoever ends the run first answers the waiter, so it is answered exactly once.
        if (!this.sparkProfileRun.compareAndSet(run, 0)) return;
        this.quietWaiter = null;
        final ProfileAnalysis.Result finalResult = result;
        this.runGlobal(() -> done.accept(finalResult));
    }

    private void awaitSparkProfile(int run, CommandSender sender, File sparkDir, long savedAfter, int attempt, boolean pluginsView, boolean upload) {
        if (this.sparkRunStale(run)) return;
        final File found = newestProfileSince(sparkDir, savedAfter);

        if (found == null) {
            if (attempt < 15) {
                this.runDelayed(() -> this.awaitSparkProfile(run, sender, sparkDir, savedAfter, attempt + 1, pluginsView, upload), 2);
                return;
            }
            this.endSparkRun(run);
            this.sparkSavingSince = 0; // nothing came, so nothing is still being written
            sep(sender);
            sender.sendMessage(ChatColor.RED + "Spark did not produce a profile. Check the console - "
                    + "another profiler may already have been running.");
            sep(sender);
            return;
        }

        try {
            final SparkProfile profile = SparkProfile.read(found);

            // On Folia each region ticks separately, so ticks are counted per region.
            final List<FoliaTicks.Region> ticking =
                    PlatformDetector.isFolia() ? FoliaTicks.regions() : null;
            final ProfileAnalysis.Result result = ProfileAnalysis.analyse(profile, WorldHotspots.pluginPackages(this),
                    ticking == null ? 1 : ticking.size());

            // The plugin/listener view needs no chunk hotspots, so it reports straight away.
            if (pluginsView) {
                this.runGlobal(() -> {
                    if (this.sparkRunStale(run)) return;

                    try {
                        final List<String> lines = ProfileReport.sendPlugins(sender, result, this.chatLinks, !(upload && this.uploadReady()));

                        if (upload) this.uploadReport(sender, lines, "catalyst-plugins");
                    } finally {
                        this.endSparkRun(run);
                    }
                });
                return;
            }

            // With experimental.spark-upload on, send the saved profile to spark first (off the
            // main thread) so the report can link the real viewer; sparkUrl is null when off or
            // if the upload did not work, and the report keeps its local-file wording.
            final Consumer<String> report = sparkUrl -> {
                // Folia can't read chunks from the global thread, so check around each player on
                // their own region. spark merges region threads, so Folia's tick times say which lags.
                if (PlatformDetector.isFolia()) {
                    final var regions = ticking;
                    FoliaHotspots.aroundPlayers(this, 3, (crowded, hoppers) -> {
                        if (this.sparkRunStale(run)) return;

                        try {
                            final boolean echo = !(upload && this.uploadReady());
                            final List<String> lines = ProfileReport.send(sender, result, crowded, hoppers, found, this.chatLinks, sparkUrl, echo);

                            if (echo) {
                                sender.sendMessage(ChatColor.DARK_GRAY + "  On Folia only chunks near online players can be checked, so a farm"
                                        + " or chunk loader with nobody near it will not be listed here.");

                                if (regions != null) {
                                    sender.sendMessage("");
                                    RegionReport.send(sender, regions, this.chatLinks, true);
                                }
                            }

                            if (upload) this.uploadReport(sender, lines, "catalyst-profile");
                        } finally {
                            this.endSparkRun(run);
                        }
                    });
                    return;
                }
                this.runGlobal(() -> {
                    if (this.sparkRunStale(run)) return;

                    try {
                        final List<WorldHotspots.Spot> crowded = WorldHotspots.crowdedChunks(this, 3);
                        final List<WorldHotspots.Spot> hoppers = WorldHotspots.blockEntityChunks(this, "HOPPER", 3);
                        final List<String> lines = ProfileReport.send(sender, result, crowded, hoppers, found, this.chatLinks,
                                sparkUrl, !(upload && this.uploadReady()));

                        if (upload) this.uploadReport(sender, lines, "catalyst-profile");
                    } finally {
                        this.endSparkRun(run);
                    }
                });
            };

            if (super.getConfig().getBoolean("experimental.spark-upload", true))
                this.runAsync(() -> report.accept(SparkUpload.upload(found).orElse(null)));
            else
                report.accept(null);
        } catch (Throwable t) {
            this.endSparkRun(run);
            sep(sender);
            sender.sendMessage(ChatColor.RED + "Could not read the spark profile: " + t);
            sep(sender);
        }
    }

    private File sparkDataFolder() {
        final var spark = super.getServer().getPluginManager().getPlugin("spark");

        if (spark != null) return spark.getDataFolder();

        // Paper's bundled spark is not a registered plugin but writes to the same place.
        return new File(PlatformDetector.serverRoot(), "plugins/spark");
    }

    private static File newestProfileSince(File dir, long since) {
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".sparkprofile"));

        if (files == null) return null;
        File newest = null;

        for (final File f : files)
            if (f.lastModified() >= since && (newest == null || f.lastModified() > newest.lastModified()))
                newest = f;

        return newest;
    }

    private volatile Benchmark activeBench;

    public boolean benchmarkRunning() {
        // A finished benchmark is let go of, so its report, bots and results can be collected.
        if (this.activeBench != null && this.activeBench.isReleased()) this.activeBench = null;

        return this.activeBench != null && (!this.activeBench.isFinished() || this.activeBench.isClosing());
    }

    /** A reason a config change or reload should wait, or null: doing it now would disrupt an active run. */
    public String activeOperation() {
        if (this.benchmarkRunning()) return "a benchmark is running";

        if (this.sparkProfileRunning()) return "a profile is running";

        if (this.lagSampleRunning()) return "a lag sample is running";

        if (this.nettyProfileRunning()) return "a packet profile is running";

        return null;
    }

    /** Whether a running benchmark should refuse the lag sampler and packet profiler right now (config bypass aside). */
    private boolean benchmarkBlocksTools(CommandSender sender) {
        if (!this.benchmarkRunning() || super.getConfig().getBoolean("benchmark.tools-during-run", false)) return false;

        sender.sendMessage(ChatColor.RED + "A benchmark is running and is using the profiler and tick measurements. Wait for"
                + " it, stop it with /catalyst perf bench cancel, or set benchmark.tools-during-run to allow this anyway.");
        return true;
    }

    /** Called by a benchmark once it has released everything, so nothing keeps it alive. */
    public void benchmarkReleased(Benchmark bench) {
        if (this.activeBench == bench) this.activeBench = null;
    }

    public void startBenchmark(CommandSender sender, boolean force, Integer botsOnly, Benchmark.Intensity intensity,
                               org.bukkit.Location roamAround, boolean spread) {
        this.activeBench = Benchmark.start(this, sender, force, botsOnly, intensity, roamAround, spread);

        if (this.activeBench != null)
            this.benchmarksRun.incrementAndGet();
    }

    /** Skips the benchmark stage the given clickable belongs to, if it is still the one running. */
    public void skipBenchmarkStage(CommandSender sender, String token) {
        if (!this.benchmarkRunning()) {
            sender.sendMessage(ChatColor.GRAY + "No benchmark is running.");
            return;
        }

        // No index (typed command): skip whatever stage is running. An index (a [skip] link):
        // skip that stage only if it is still the current one.
        int index = -1;

        try {
            if (!token.isBlank()) index = Integer.parseInt(token.trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        this.activeBench.requestSkip(index);
    }

    public void cancelBenchmark(CommandSender sender) {
        sep(sender);

        if (!this.benchmarkRunning()) {
            sender.sendMessage(ChatColor.GRAY + "No benchmark is running.");
        } else if (this.activeBench.isClosing()) {
            sender.sendMessage(ChatColor.GRAY + "Already cancelled; its worlds and bots are still being removed.");
        } else {
            this.activeBench.cancel();
            sender.sendMessage(ChatColor.AQUA + "Benchmark cancelled." + ChatColor.GRAY
                    + " Its worlds and any bots are being removed; nothing will be reported.");
        }
        sep(sender);
    }

    /** Benchmarks started since bStats last asked; it reads and resets this. */
    private final AtomicInteger benchmarksRun = new AtomicInteger();

    public void runAsync(Runnable task) { this.scheduler.runAsync(this, this.guarded("background", task)); }
    public void runDelayed(Runnable task, long seconds) { this.scheduler.runDelayedAsync(this, this.guarded("scheduled", task), seconds); }
    public void runGlobal(Runnable task) { this.scheduler.runGlobal(this, this.guarded("main thread", task)); }

    public CatalystEngine engine() { return this.engine; }
    public RuntimeManager runtime() { return this.runtime; }
    public ProtectionManager protection() { return this.protection; }
    public ReportUploader uploader() { return this.uploader; }
    public TpsSource tpsSource() { return this.tpsSource; }
    public TpsWatchdog tpsWatchdog() { return this.tpsWatchdog; }
    public ScanResult lastScan() { return this.lastScan; }
    public LibraryDownloader libraries() { return this.libraries; }

    /**
     * Ids of the settings the most recent scan still recommends - the only ones worth offering to
     * apply (config fix tab-completion). Falls back to every id when nothing has been scanned yet,
     * so completion still works before the first scan.
     */
    public java.util.Set<String> pendingOptimizationIds() {
        final ScanResult scan = this.lastScan;

        if (scan == null) return this.engine.optimizationIds();
        final java.util.Set<String> ids = new java.util.LinkedHashSet<>();

        for (final Optimization opt : scan.findings().keySet()) ids.add(opt.id());

        return ids;
    }
}

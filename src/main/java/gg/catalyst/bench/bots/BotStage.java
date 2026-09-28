// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import gg.catalyst.Catalyst;
import gg.catalyst.libs.LibraryDownloader;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.util.Branding;
import gg.catalyst.util.WorldPaths;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The benchmark stage with real connections: bots join over loopback, move into the bench world,
 * walk so terrain keeps loading, and leave when the stage ends. The safety limits are in code, not
 * config: loopback only, at most {@link BotProcess#MAX_BOTS}, reserved names a real player must not
 * hold, and a per-stage token each bot must present for any special treatment - including, on an
 * online-mode server, skipping the Mojang check (see {@link OnlineModeBypass}). Bots leave no data behind.
 */
public final class BotStage implements Listener {
    public static final String DEFAULT_PREFIX = "cat_bot_";
    /**
     * A usable name prefix: letters, digits and underscores, short enough that the prefix and
     * a bot's number stay within Minecraft's 16 characters, and not ending in a digit, so
     * bot1 + 1 can never be read as bot11.
     */
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{0,12}[A-Za-z_]");

    /** benchmark.bots.name-prefix, or the default when it is unset or not a usable prefix. */
    public static String namePrefix(Catalyst plugin) {
        final String configured = plugin.getConfig().getString("benchmark.bots.name-prefix", DEFAULT_PREFIX);

        if (configured != null && PREFIX.matcher(configured).matches()) return configured;
        plugin.getLogger().warning("benchmark.bots.name-prefix \"" + configured + "\" is not usable: 1-13 letters, digits"
                + " or underscores, not ending in a digit. Using " + DEFAULT_PREFIX + ".");

        return DEFAULT_PREFIX;
    }

    private final Catalyst plugin;
    private final int count;
    /** The bots' names are this and a number: cat_bot_1, cat_bot_2, ... unless configured. */
    private final String prefix;
    private final ForwardingPlan forwarding;
    private final Set<String> names = new HashSet<>();
    private World world;
    /** Lane width in chunks and the flat ground height, set when the stage starts. */
    private int laneChunks, spawnY;
    private BotProcess process;
    private Listener spawnListener;
    private Listener loginListener;
    /** On an online-mode server, lets this stage's bots skip the Mojang check; null otherwise. */
    private OnlineModeBypass bypass;
    /** Set once the stage is ending, so Catalyst's own kicks go through. */
    private volatile boolean releasing;

    private BotStage(Catalyst plugin, int count, ForwardingPlan forwarding, String prefix) {
        this.plugin = plugin;
        this.count = count;
        this.forwarding = forwarding;
        this.prefix = prefix;
        this.metadataKey = this.metadataKeyFromConfig();

        for (int i = 1; i <= count; i++) this.names.add(prefix + i);
    }

    /** The first bot's name, for pointing at where it spawns. */
    public String firstName() { return this.prefix + 1; }

    /** Either a reason the stage cannot run here, or a stage ready to {@link #start}. */
    public sealed interface Plan permits Refused, Ready {}
    public record Refused(String reason) implements Plan {}
    public record Ready(BotStage stage) implements Plan {}

    /**
     * The most bots a run may use: benchmark.bots.max-count (default 100), never above the
     * absolute {@link BotProcess#MAX_BOTS} ceiling. The memory guard still stops a run that
     * would run the server out, so this is a convenience limit, not the safety one.
     */
    public static int maxBots(Catalyst plugin) {
        final int configured = plugin.getConfig().getInt("benchmark.bots.max-count", 100);

        return Math.max(1, Math.min(BotProcess.MAX_BOTS, configured));
    }

    /** @param countOverride bots asked for on the command line, or null for benchmark.bots.count */
    public static Plan plan(Catalyst plugin, Integer countOverride) {
        final int asked = countOverride != null ? countOverride : plugin.getConfig().getInt("benchmark.bots.count", 20);
        final int count = Math.max(1, Math.min(maxBots(plugin), asked));

        final LibraryDownloader libs = plugin.libraries();

        if (libs == null || !libs.isReady())
            return new Refused("the bot libraries are not ready ("
                    + (libs == null ? "not started" : libs.detail()) + ")");

        // Online mode makes the server verify each login with Mojang, but bots have no
        // accounts. On Mojang-mapped Paper (1.20.5+) Catalyst lets its own bots - loopback,
        // stage token - skip that check; see OnlineModeBypass. Older Paper, Spigot and
        // CraftBukkit expose no such hook, so there online mode still rules the stage out.
        if (Bukkit.getOnlineMode() && !OnlineModeBypass.supported())
            return new Refused("the server is in online mode, and only Paper 1.20.5+ lets " + Branding.name()
                    + " admit its own bots without Minecraft accounts. Switch to offline mode, or run the"
                    + " bots on a Paper 1.20.5+ server.");

        // On a proxy backend the bots supply the player data the proxy would, over loopback only.
        final ForwardingPlan forwarding = forwardingPlan();

        if (forwarding.problem() != null) return new Refused(forwarding.problem());

        final String ip = Bukkit.getIp();

        if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0") && !ip.equals("127.0.0.1") && !ip.equals("localhost"))
            return new Refused("the server only listens on " + ip + ", and bots may only connect to 127.0.0.1.");

        // In a container the bot process shares the server's memory limit; going over it
        // gets the largest process killed, which is the server.
        final OptionalLong headroom = ContainerLimits.memoryHeadroom();
        final long needed = ContainerLimits.botHeapBytes(count) + ContainerLimits.JVM_OVERHEAD_BYTES;

        if (headroom.isPresent() && headroom.getAsLong() < needed) {
            final String pinned = ContainerLimits.heapReservedFraction() >= 0.9
                    ? " The heap is also pinned (-Xms at or near -Xmx), so all of that -Xmx is held from boot even when"
                      + " unused; setting -Xms below -Xmx, or lowering -Xmx, is what actually frees the room."
                    : "";
            return new Refused("the container's memory limit (" + ContainerLimits.mib(ContainerLimits.limit().orElse(0))
                    + " MB) leaves " + ContainerLimits.mib(headroom.getAsLong()) + " MB beyond what the server itself may grow to"
                    + " (its -Xmx of " + ContainerLimits.mib(ContainerLimits.serverMaxHeap()) + " MB, plus Java's own overhead),"
                    + " and " + count + " bots need about " + ContainerLimits.mib(needed) + " MB in a process of their own."
                    + " Running them could get the server killed for going over the limit. Lower the server's -Xmx"
                    + " so there is room, or raise the limit. How you lower -Xmx depends on the host: some panels"
                    + " have a memory or 'heap limit' setting, some let you edit the -Xmx flag or startup command"
                    + " yourself, and self-hosted you set it in your start script. /catalyst status shows the"
                    + " current -Xmx, so restart and check it changed." + pinned);
        }

        // The bots' CPU is only kept out of the figures if there is a core to spare for it.
        final int cores = Runtime.getRuntime().availableProcessors();

        if (cores < 2)
            return new Refused("the server has " + cores + " CPU core available, so the bots would compete with"
                    + " it and the figures would be meaningless.");

        // A whitelist, bans or a full server do not stop the stage: the login listeners let
        // exactly these bots through, when they prove themselves. See verifyLogin().

        final String prefix = namePrefix(plugin);
        final Set<String> ours = readMarker(plugin);

        for (final Player p : Bukkit.getOnlinePlayers())
            // Only names shaped like a bot's: a short prefix such as "bot" must not refuse "botany".
            if (p.getName().toLowerCase(Locale.ROOT).matches(Pattern.quote(prefix.toLowerCase(Locale.ROOT)) + "\\d{1,2}"))
                return new Refused(p.getName() + " is online, and " + prefix + " names are reserved for the bots."
                        + " Set another benchmark.bots.name-prefix, or run it when they are offline.");
        final List<String> taken = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            final String name = prefix + i;
            final OfflinePlayer known = Bukkit.getOfflinePlayer(offlineUuid(name));

            // In offline mode a bot named like a past player would inherit their inventory
            // and position, and move them around the world.
            if (known.hasPlayedBefore() && !ours.contains(name)) taken.add(name);

            // In online mode a real account of that name has a different, online UUID, so the
            // lookup above misses it. The bot could not reach its data (that goes by UUID), but
            // plugins keyed by name could still mistake the bot for it. Paper's name cache knows
            // every account that has joined; online mode is only possible here on Paper anyway.
            else if (Bukkit.getOnlineMode() && realAccountNamed(name)) taken.add(name);
        }

        if (!taken.isEmpty())
            return new Refused(String.join(", ", taken) + (taken.size() == 1 ? " has" : " have")
                    + " played here before, and a bot under that name could take over their data or be"
                    + " mistaken for them.");
        final BotStage stage = new BotStage(plugin, count, forwarding, prefix);

        // The name cache above only remembers recent players, so in online mode also ask Mojang
        // who owns these names. It runs in the background now and is settled in start(), well
        // before which it will have finished; the server thread never waits on the network.
        if (Bukkit.getOnlineMode()) stage.lookUpOwners();
        stage.lookUpSkins();

        return new Ready(stage);
    }

    /** In online mode: name to the real account holding it, from Mojang. Null offline. */
    private CompletableFuture<Map<String, UUID>> owners;

    /** Skins to hand out, fetched from Mojang in the background; null when none are set up. */
    private CompletableFuture<List<MojangNames.Skin>> skins;
    private final Random skinPicker = new Random();

    /**
     * Fetches the skins of the players named in benchmark.bots.skins, purely for looks. Paper
     * only: it is the only platform that lets a plugin change a profile at login.
     */
    @SuppressWarnings("deprecation") // getDescription(): its replacement is Paper-only
    private void lookUpSkins() {
        final List<String> wanted = this.plugin.getConfig().getStringList("benchmark.bots.skins").stream()
                .map(String::trim).filter(n -> n.matches("[A-Za-z0-9_]{1,16}")).distinct().limit(20).toList();

        if (wanted.isEmpty()) return;

        if (!PaperSkins.supported()) {
            this.plugin.getLogger().info("benchmark.bots.skins needs Paper; the bots keep their default skins here.");
            return;
        }
        final String userAgent = Branding.name() + "/" + this.plugin.getDescription().getVersion();
        this.skins = new CompletableFuture<>();
        this.plugin.runAsync(() -> {
            try {
                final List<MojangNames.Skin> found = MojangNames.skins(wanted, userAgent);

                if (found.size() < wanted.size())
                    this.plugin.getLogger().info("Bot skins: found " + found.size() + " of the " + wanted.size()
                            + " players in benchmark.bots.skins; the rest do not exist or have no skin.");
                this.skins.complete(found);
            } catch (Exception e) {
                this.plugin.getLogger().info("Bot skins: could not reach Mojang (" + e.getClass().getSimpleName()
                        + "), so the bots keep their default skins.");
                this.skins.complete(List.of());
            }
        });
    }

    /** Dresses a verified bot in one of the fetched skins, at random. Never delays a login. */
    private void applySkin(AsyncPlayerPreLoginEvent event) {
        final List<MojangNames.Skin> ready = this.skins == null ? null : this.skins.getNow(null);

        if (ready == null || ready.isEmpty()) return;
        final MojangNames.Skin skin = ready.get(this.skinPicker.nextInt(ready.size()));

        try {
            PaperSkins.apply(event, skin);
        } catch (RuntimeException | LinkageError e) {
            // Only looks: a bot without its skin is still a bot.
        }
    }

    @SuppressWarnings("deprecation") // getDescription(): its replacement is Paper-only
    private void lookUpOwners() {
        final String userAgent = Branding.name() + "/" + this.plugin.getDescription().getVersion();
        this.owners = new CompletableFuture<>();
        this.plugin.runAsync(() -> {
            try {
                this.owners.complete(MojangNames.owners(this.names, userAgent));
            } catch (Exception e) {
                this.owners.completeExceptionally(e);
            }
        });
    }

    /**
     * Refuses to start if a real account owning one of the bot names has played here, since
     * plugins that go by name could mistake the bot for that player. If Mojang could not be
     * asked, it refuses too: without the answer there is no knowing.
     */
    /** True while the online-mode owner lookup is still outstanding, so the bots are not yet safe to start. */
    public boolean awaitingOwners() {
        return this.owners != null && !this.owners.isDone();
    }

    private void refuseRealAccounts() throws IOException {
        if (this.owners == null) return;

        // This runs on the server thread, so it never waits: the lookup has had the stages before
        // this one, and the roaming run its own pause, to finish; if Mojang has still not answered,
        // the answer is no.
        if (!this.owners.isDone())
            throw new IOException("Mojang did not answer in time who owns the bot names, and an online-mode server"
                    + " must know that before the bots join, so a bot cannot take a real player's name. Usually"
                    + " Mojang was just slow - try again in a moment. An offline-mode server skips this check");
        Map<String, UUID> found;

        try {
            found = this.owners.join();
        } catch (Exception e) {
            final Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            throw new IOException("could not ask Mojang who owns the bot names ("
                    + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage())
                    + "), and in online mode that must be known first");
        }
        final List<String> played = new ArrayList<>();

        for (final Map.Entry<String, UUID> e : found.entrySet())
            if (Bukkit.getOfflinePlayer(e.getValue()).hasPlayedBefore()) played.add(e.getKey());

        if (!played.isEmpty())
            throw new IOException(String.join(", ", played) + (played.size() == 1 ? " is a real account that has"
                    : " are real accounts that have") + " played here, and a bot under that name could be mistaken for them");
    }

    /** Whether an account other than the bot's own offline one has joined under this name. */
    private static boolean realAccountNamed(String name) {
        try {
            final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            return cached != null && !cached.getUniqueId().equals(offlineUuid(name));
        } catch (NoSuchMethodError notPaper) {
            return false; // Only reachable on Paper, which has it; see plan().
        }
    }

    public int count() { return this.count; }

    /** How full the server's container is, 0 to 1, or empty outside a container with a limit. */
    public static OptionalDouble containerFullness() {
        return ContainerLimits.fullness();
    }

    /**
     * A caution when the heap is pinned on a limited container, or null. Pinning (-Xms = -Xmx) is
     * normal for a live server, but it means a run's memory cannot be handed back until a restart,
     * so a second bots run may be refused for room that only a restart frees.
     */
    public static String pinnedHeapWarning() {
        if (ContainerLimits.limit().isEmpty() || ContainerLimits.heapReservedFraction() < 0.9) return null;

        return "the heap is pinned (-Xms at or near -Xmx), so the server holds its whole -Xmx from boot and a run's"
                + " memory cannot be handed back until a restart. Fine for normal play; but to benchmark bots here"
                + " more than once without restarting, set -Xms below -Xmx or lower -Xmx to leave the container room.";
    }

    /** First chunk of the bot lanes: far from every other stage's chunks, on both axes. */
    public static final int LANES_FROM_CHUNK = 1250;

    /** Where a bot starts: around the player when roaming, else the head of its own lane (walked towards +x). */
    public Location spawnFor(String name) {
        if (this.roamCenter != null) return this.roamSpawns.getOrDefault(name, this.roamCenter);
        final int lane = laneOf(name);

        return new Location(this.world, LANES_FROM_CHUNK * 16 + 8.5, this.spawnY, (LANES_FROM_CHUNK + lane * this.laneChunks) * 16 + 8.5);
    }

    /** Bot 1 walks lane 0, bot 2 lane 1, and so on: the number the name ends in, less one. */
    static int laneOf(String name) {
        final Matcher m = Pattern.compile("(\\d{1,2})$").matcher(name);

        return m.find() ? Math.max(0, Integer.parseInt(m.group(1)) - 1) : 0;
    }

    /** The view distance chunks are sent to a bot at; Spigot before per-world views has only the server's. */
    private static int viewDistance(World w) {
        try {
            return w.getViewDistance();
        } catch (NoSuchMethodError older) {
            return Bukkit.getViewDistance();
        }
    }

    /** Launches the bots. They are moved into {@code benchWorld} as they join. */
    public void start(World benchWorld) throws IOException {
        // Each bot gets a lane of its own, well away from the other stages' chunks: lanes are
        // wider than a bot's view on both sides, and every bot walks the same way along its
        // lane, so no two bots ever load or generate the same chunk.
        final int view = viewDistance(benchWorld);
        this.laneChunks = 2 * view + 3;

        // The bench world is flat, so one height fits every lane; read here, where the chunk
        // is already loaded, since the spawn event may ask from another thread.
        this.spawnY = benchWorld.getHighestBlockYAt(8, 8) + 1;
        this.launch(benchWorld, false);
    }

    // ── Roaming a real world ─────────────────────────────────────────────

    /** How far from where the run started a bot may wander, in blocks. */
    private static final int ROAM_LEASH = 24;
    /** A bot still walking a route after this long gets a new one anyway: it may be stuck. */
    private static final long ROUTE_MAX_MILLIS = 30_000;
    /**
     * Fewest routes planned per half-second: planning is server-thread work, measured with the
     * bots, so it is rationed. The ration grows with the bot count (see steer) so a big run does
     * not leave most bots standing.
     */
    private static final int ROUTES_PER_STEER = 4;
    /** Where the last steer stopped, so the next one carries on round the bots rather than restarting. */
    private int steerCursor;

    /** Where roaming bots start and are kept near; null for the bench lanes. */
    private Location roamCenter;
    private WorldTerrain terrain;
    /** Each bot's spawn spot, worked out on the server thread before any join asks from another. */
    private final Map<String, Location> roamSpawns = new ConcurrentHashMap<>();
    /** Spread runs: each bot's own home, which its leash is measured from. Empty when clustered. */
    private final Map<String, Location> roamHomes = new HashMap<>();
    private final Map<String, Long> routedAt = new HashMap<>();
    private final Random roamRandom = new Random();
    private org.bukkit.scheduler.BukkitTask roamTask;
    private Listener roamGuard, vibrationGuard;

    /**
     * Launches the bots into the real world around {@code center}, wandering on routes the
     * server plans (RoamPaths) and kept from changing anything (RoamGuard). With a
     * {@code spreadRadius} each bot gets a home of its own scattered within it, as real players
     * are, instead of all crowding the one spot; 0 keeps them together.
     */
    public void startRoaming(Location center, int spreadRadius) throws IOException {
        this.roamCenter = center.clone();
        this.terrain = new WorldTerrain(center.getWorld());

        // Round the spawn, asked from the console, these may not be loaded (1.21.9 dropped
        // spawn chunks), and the spots need reading.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                center.getWorld().getChunkAt((center.getBlockX() >> 4) + dx, (center.getBlockZ() >> 4) + dz);

        for (int i = 1; i <= this.count; i++) {
            final Location home = spreadRadius > 0 ? this.homeFor(center, i, spreadRadius) : null;

            if (home != null) this.roamHomes.put(this.prefix + i, home);
            this.roamSpawns.put(this.prefix + i, home != null ? home : this.spotNear(center, i));
        }
        this.launch(center.getWorld(), true);
        Bukkit.getPluginManager().registerEvents(this.roamGuard = new RoamGuard(), this.plugin);

        try {
            Bukkit.getPluginManager().registerEvents(this.vibrationGuard = new VibrationGuard(), this.plugin);
        } catch (Throwable before117) {
            this.vibrationGuard = null; // No sculk before 1.17, so nothing to set off.
        }
        this.roamTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::steer, 20L, 10L);
    }

    /**
     * Somewhere to stand a few blocks from {@code c}, spread round it bot by bot; else on the
     * ground under {@code c} itself; else the top of that column. A spawn point can be a block
     * or two in the air, and a bot put there would hang where it was put.
     */
    private Location spotNear(Location c, int i) {
        final double angle = i * 2.39996, r = 2 + (i % 4) * 1.5;
        final Location near = this.groundAt(c, (int) Math.floor(c.getX() + Math.cos(angle) * r),
                (int) Math.floor(c.getZ() + Math.sin(angle) * r));

        if (near != null) return near;
        final Location under = this.groundAt(c, c.getBlockX(), c.getBlockZ());

        if (under != null) return under;

        return new Location(c.getWorld(), c.getBlockX() + 0.5, c.getWorld().getHighestBlockYAt(c) + 1, c.getBlockZ() + 0.5);
    }

    /** benchmark.bots.spread-radius: how far from the centre a spread run's homes may be, 32-2000. */
    public static int spreadRadius(Catalyst plugin) {
        return Math.max(32, Math.min(2000, plugin.getConfig().getInt("benchmark.bots.spread-radius", 256)));
    }

    /** Tries per bot at finding an already-generated home before it falls back to the centre. */
    private static final int HOME_TRIES = 6;

    /**
     * A home for bot {@code i} of a spread run: spaced evenly over the disc (a sunflower spiral,
     * so no two land on top of each other), jittered on retries. Only chunks that already exist
     * are used - loading one from disk is cheap, generating one would stall the server thread
     * before a single bot is on. Null when nowhere usable turned up.
     */
    private Location homeFor(Location c, int i, int radius) {
        final World w = c.getWorld();

        for (int attempt = 0; attempt < HOME_TRIES; attempt++) {
            final double t = attempt == 0 ? (i - 0.5) / this.count : this.roamRandom.nextDouble();
            final double angle = i * 2.39996 + attempt * 1.1, r = radius * Math.sqrt(t);
            final int x = (int) Math.floor(c.getX() + Math.cos(angle) * r), z = (int) Math.floor(c.getZ() + Math.sin(angle) * r);

            if (!w.isChunkGenerated(x >> 4, z >> 4)) continue;
            w.getChunkAt(x >> 4, z >> 4);

            // The Nether's highest block is its roof, so there the search starts at the player's height.
            final int top = w.getEnvironment() == World.Environment.NORMAL ? w.getHighestBlockYAt(x, z) + 1 : c.getBlockY();
            final Location ground = this.groundAt(new Location(w, x, top, z), x, z);

            if (ground != null) return ground;
        }

        return null;
    }

    /** Standing room in column x,z from a little above {@code c} down to a dozen blocks below, or null. */
    private Location groundAt(Location c, int x, int z) {
        for (int y = c.getBlockY() + 3; y >= c.getBlockY() - 12; y--) {
            final double stand = this.terrain.standAt(x, y, z);

            if (!Double.isNaN(stand) && this.terrain.open(x, y, z) && this.terrain.open(x, y + 1, z))
                return new Location(c.getWorld(), x + 0.5, stand, z + 0.5, (float) (this.roamRandom.nextDouble() * 360), 0f);
        }

        return null;
    }

    /**
     * Every half second, a new route for each roaming bot that has finished its last, been
     * knocked off it, or been on it too long. A route is a walk of 6 to 17 blocks to a random
     * spot within the leash.
     */
    private void steer() {
        if (this.process == null || this.releasing) return;
        final long now = System.currentTimeMillis();

        // A stable ring of the bots, walked from where the last steer stopped, so every bot gets
        // its turn rather than the same few at the front of the world's list taking the ration
        // each pass - which left most of a big run standing.
        final List<Player> ring = new ArrayList<>();

        for (final Player p : this.world.getPlayers()) if (this.isVerifiedBot(p)) ring.add(p);

        if (ring.isEmpty()) return;
        ring.sort(Comparator.comparing(Player::getName));

        // The ration grows with the count so all the bots cycle, not just four of them; planning
        // stays bounded per pass, and is server-thread work counted as part of what the bots cost.
        final int budget = Math.max(ROUTES_PER_STEER, (ring.size() + 7) / 8);
        final int n = ring.size();
        int planned = 0, seen = 0, i = this.steerCursor % n;

        while (seen < n && planned < budget) {
            final Player p = ring.get(i);
            i = (i + 1) % n;
            seen++;
            final String name = p.getName();

            // takeIdle clears the bot's request, so it is only reached for a bot within the pass's
            // budget; a bot past it keeps its request for next time rather than being cleared here.
            final Long last = this.routedAt.get(name);

            if (last != null && !this.process.takeIdle(name) && now - last < ROUTE_MAX_MILLIS) continue;
            planned++;
            this.routedAt.put(name, now);
            final Location at = p.getLocation();

            // The bot's own spawn spot, which spotNear/homeFor already proved standable - unlike
            // roamCenter, which comes straight from the command and may sit over water or air.
            final Location home = this.roamSpawns.getOrDefault(name, this.roamCenter);

            // Mid-jump, or put somewhere with nothing under it: plan from the ground below.
            int y = (int) Math.ceil(at.getY() - 0.01);

            for (int down = 0; down < 8 && Double.isNaN(this.terrain.standAt(at.getBlockX(), y, at.getBlockZ())); down++) y--;

            // No ground below at all: the bot is over water or in the air, where it has no gravity
            // of its own and cannot be routed from. Put it back on its home ground - but only if
            // that is itself solid, so a home over water cannot make it teleport there every pass.
            if (Double.isNaN(this.terrain.standAt(at.getBlockX(), y, at.getBlockZ()))) {
                if (!Double.isNaN(this.terrain.standAt(home.getBlockX(), home.getBlockY(), home.getBlockZ())))
                    try { p.teleport(home); } catch (Throwable ignored) { /* Folia cross-region; next pass retries */ }
                this.routedAt.remove(name);
                continue;
            }
            final List<RoamPaths.Point> route = RoamPaths.wander(this.terrain, at.getBlockX(), y,
                    at.getBlockZ(), home.getBlockX(), home.getBlockZ(), 6 + this.roamRandom.nextInt(12),
                    ROAM_LEASH, this.roamRandom, 800);

            // Nowhere to go from here yet (still in the air, or boxed in): try again next time.
            if (route.isEmpty()) this.routedAt.remove(name);

            if (!route.isEmpty())
                this.process.sendRoute(name, route.stream().map(q -> new double[]{q.x(), q.y(), q.z(), q.roomToJump() ? 1 : 0}).toList());
        }
        this.steerCursor = i;
    }

    /**
     * Keeps roaming bots from changing a real world or dying in it. They take knockback and
     * damage like players, but never a blow that would kill them - a bot has no one to
     * press respawn. Mobs ignore them, so nothing chases them into a base (or blows up next
     * to one); they cannot pick anything up, trample farmland or press plates, and do not go
     * hungry.
     */
    private final class RoamGuard implements Listener {
        private boolean bot(Object entity) {
            return entity instanceof Player p && BotStage.this.isGuardedBot(p);
        }

        @EventHandler(ignoreCancelled = true)
        public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
            if (this.bot(event.getTarget())) event.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onStep(PlayerInteractEvent event) {
            if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL && this.bot(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
            if (this.bot(event.getEntity())) event.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onHunger(org.bukkit.event.entity.FoodLevelChangeEvent event) {
            if (this.bot(event.getEntity())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onHurt(org.bukkit.event.entity.EntityDamageEvent event) {
            if (this.bot(event.getEntity()) && event.getFinalDamage() >= ((Player) event.getEntity()).getHealth())
                event.setCancelled(true);
        }
    }

    /** Sculk hears footsteps and fires redstone; the bots' steps must not. Separate, as it is 1.17+. */
    private final class VibrationGuard implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onVibration(org.bukkit.event.block.BlockReceiveGameEvent event) {
            if (event.getEntity() instanceof Player p && BotStage.this.isGuardedBot(p)) event.setCancelled(true);
        }
    }

    private void launch(World w, boolean roam) throws IOException {
        this.refuseRealAccounts();
        this.world = w;
        this.startedAt = System.currentTimeMillis();
        this.writeMarker();
        recordStart(this.plugin, this.startedAt);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.spawnListener = this.spawnListener();

        if (this.spawnListener != null) Bukkit.getPluginManager().registerEvents(this.spawnListener, this.plugin);
        this.loginListener = this.loginListener();
        Bukkit.getPluginManager().registerEvents(this.loginListener, this.plugin);

        // On an online-mode server, let this stage's bots past the Mojang check - and only
        // them: loopback, carrying the stage token, under one of this stage's bot names. plan()
        // has already refused if the server is online-mode and this hook is unavailable, so
        // here it is installed only when it works.
        if (Bukkit.getOnlineMode()) {
            this.bypass = OnlineModeBypass.install(Bukkit.getServer(), this.plugin.getLogger(),
                    (host, from) -> !this.releasing && from.isLoopbackAddress() && this.carriesToken(host),
                    names::contains);

            if (this.bypass != null)
                this.plugin.getLogger().info("Server is in online mode: this stage's bots will skip the Mojang"
                        + " check, but only from 127.0.0.1 and only carrying the stage token. Every other"
                        + " login authenticates as usual, and this is undone when the stage ends.");
            else
                this.plugin.getLogger().warning("Server is in online mode but the bot login bypass could not be"
                        + " installed, so the bots will fail to authenticate. See any error above.");
        }

        final String version = minecraftVersion(Bukkit.getBukkitVersion());

        // Long enough for the whole stage, so the child never quits early on its own;
        // the stage stops it, and the child stops itself if the stage never does.
        final int seconds = 180;
        this.process = BotProcess.launch(new File(this.plugin.getDataFolder(), "bots"), this.plugin.libraries().verifiedFiles(),
                Bukkit.getPort(), this.count, seconds, version, this.prefix, this.forwarding.line(), this.identityHost, roam, this.plugin.getLogger());
    }

    /**
     * The Minecraft version at the front of the API version: "1.21.11-R0.1-SNAPSHOT" and
     * Paper 26's "26.2.build.129-stable" both lead with it.
     */
    static String minecraftVersion(String bukkitVersion) {
        final Matcher m = Pattern.compile("^\\d+(\\.\\d+)*").matcher(bukkitVersion);

        return m.find() ? m.group() : bukkitVersion;
    }

    /** "BungeeCord forwarding" / "Velocity forwarding" when the bots joined through it, else null. */
    public String forwardingLabel() { return this.forwarding.label(); }

    /**
     * Proof that a connection is this stage's bot, not someone using a bot's name: every bot
     * puts this host in its handshake. It is 128 random bits, new for every stage, and only
     * ever travels to the bot process on its stdin - never a command line, a file or config.
     */
    private final String identityHost = "catalyst-" + HexFormat.of().formatHex(randomBytes(16)) + ".invalid";

    /** Names that presented the token at login. Everything past login trusts only this set. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    private static byte[] randomBytes(int n) {
        final byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);

        return b;
    }

    /**
     * Checks a login against every condition, and records it as verified when all hold: the
     * stage is running, the name is one of its bots, the connection comes from this machine,
     * and the handshake carried the stage's token. The token is what a real player or a
     * program on the same host cannot supply; loopback is kept as a second, independent check.
     */
    boolean verifyLogin(String name, InetAddress address, String handshakeHost) {
        if (this.world == null || !this.names.contains(name)) return false;
        final boolean fromHere = address != null && address.isLoopbackAddress();

        // Checked even while the stage is releasing. Giving the name up then matters: the final
        // sweep, a few seconds on, would otherwise take the newcomer for the bot that held it.
        if (!fromHere || handshakeHost == null || !this.carriesToken(handshakeHost)) {
            // Someone using a bot's name without the token: in offline mode they share the
            // bot's UUID and so its player file, which must now never be deleted.
            this.giveUpName(name);
            return false;
        }

        // With the token it is ours, even a bot still mid-login as the stage ended: proven like
        // the rest, so the final sweep removes it and its file.
        this.verified.add(name);

        return true;
    }

    /** Bot names someone else logged in with during this stage. Never cleaned up. */
    private final Set<String> yielded = ConcurrentHashMap.newKeySet();

    /**
     * Gives a bot name up: takes it off the cleanup list on disk (which the startup sweep
     * also reads) so neither this stage nor a later sweep deletes that player's file or
     * kicks them, and says why in the console.
     */
    private void giveUpName(String name) {
        if (!this.yielded.add(name)) return;
        this.verified.remove(name);
        unmark(this.plugin, name);
        this.plugin.getLogger().warning("Someone logged in as " + name + " during the benchmark without being one of "
                + Branding.name() + "'s bots. That name's player data will be left alone; if it was not a real"
                + " player, delete it by hand. Future bot runs will refuse that name while the data exists.");
    }

    /**
     * Servers report the handshake host as-is, as "host:port", or with BungeeCord data after
     * it; the token is always the part before any of that. Compared in constant time.
     */
    private boolean carriesToken(String handshakeHost) {
        return hostMatches(handshakeHost, this.identityHost);
    }

    /**
     * The forms a server reports the handshake host in: plain; "host:port"; and with
     * BungeeCord forwarding, "host\0ip\0uuid[\0properties]" (Spigot keeps only the first
     * part, but not every fork does). Velocity forwarding leaves the host untouched.
     */
    static boolean hostMatches(String handshakeHost, String identityHost) {
        final String host = handshakeHost.split("[:\u0000]", 2)[0].toLowerCase(Locale.ROOT);

        return MessageDigest.isEqual(host.getBytes(StandardCharsets.UTF_8),
                identityHost.getBytes(StandardCharsets.UTF_8));
    }

    /** A player that passed verifyLogin, and is still connected from loopback. */
    boolean isVerifiedBot(Player player) {
        if (this.releasing || !this.verified.contains(player.getName())) return false;
        final InetSocketAddress a = player.getAddress();

        return a != null && a.getAddress() != null && a.getAddress().isLoopbackAddress();
    }

    /**
     * For the roaming guards: a verified bot, still so while the stage is releasing. The bots
     * take a second or two to leave after stop(), and in a real world that is time enough to
     * pick up someone's items - gone with the bot's data - or die in front of players. Only
     * ever used to restrict a bot, so it is safe to hold on past isVerifiedBot.
     */
    private boolean isGuardedBot(Player player) {
        if (!this.verified.contains(player.getName())) return false;
        final InetSocketAddress a = player.getAddress();

        return a != null && a.getAddress() != null && a.getAddress().isLoopbackAddress();
    }

    boolean isVerifiedName(String name) {
        return !this.releasing && this.verified.contains(name);
    }

    /**
     * First gate: bans, whitelist and plugins that refuse during pre-login. Only Paper tells
     * this event the handshake host; on Spigot bots get no pass here, but Spigot checks bans
     * and the whitelist in PlayerLoginEvent, which the login listener covers.
     *
     * On an online-mode server the Mojang check has already happened by now, or been skipped
     * for this connection by {@link OnlineModeBypass}; either way this only decides bans,
     * whitelist and the full server, exactly as in offline mode.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String host;

        try {
            host = event.getHostname();
        } catch (NoSuchMethodError spigot) {
            return;
        }

        if (!this.verifyLogin(event.getName(), event.getAddress(), host)) return;

        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) event.allow();
        this.applySkin(event);
    }

    /** Plugins that kick after join (anti-bot, auth, anti-cheat) would end the stage early. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(PlayerKickEvent event) {
        if (this.isVerifiedBot(event.getPlayer())) event.setCancelled(true);
    }

    /** Paper's login event where it exists; PlayerLoginEvent on Spigot and CraftBukkit. */
    private Listener loginListener() {
        try {
            Class.forName("io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent");
            return new PaperBotLoginListener(this::verifyLogin);
        } catch (ClassNotFoundException notPaper) {
            return new BukkitBotLoginListener(this::verifyLogin);
        }
    }

    String identityHost() { return this.identityHost; }

    /**
     * Fallback for servers without the spawn-location event (plain CraftBukkit). The bots stand
     * still for a second after being placed, so this teleport reaches them before they walk;
     * the spawn event is still preferred wherever it exists, as it avoids the extra move.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        if (this.world == null) return;

        // A bot name that joined without verifying (a path the login checks did not cover).
        if (this.names.contains(player.getName()) && !this.releasing && !this.verified.contains(player.getName())) {
            this.giveUpName(player.getName());
            return;
        }

        if (!this.isVerifiedBot(player)) return;
        this.noteWorld(player);
        this.locateSave(player.getName());

        if (!player.getWorld().equals(this.world)) player.teleport(this.spawnFor(player.getName()));
    }

    /**
     * Pins down where the server really writes this bot's files: saves it a tick after it
     * joins, then records the save holding a fresh copy. Cleanup deletes there and nowhere
     * else, and the record outlives a crash, so startup cleanup knows too.
     */
    private void locateSave(String name) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            final Player online = Bukkit.getPlayerExact(name);

            if (online == null || !this.isVerifiedBot(online)) return;
            online.saveData();
            final String uuid = offlineUuid(name).toString();
            final long cutoff = this.startedAt - 2000;

            for (final File save : this.searchSaves())
                for (final String path : List.of("playerdata/" + uuid + ".dat", "players/data/" + uuid + ".dat")) {
                    final File file = new File(save, path);

                    if (file.isFile() && file.lastModified() >= cutoff && this.recordedSaves.add(save))
                        recordSave(this.plugin, save);
                }
        }, 1L);
    }

    /**
     * Metadata other plugins can check to tell Catalyst's bots from players
     * ({@code player.hasMetadata("CatalystBot")}). Deliberately not "NPC": that is the key many
     * plugins already skip, and a bot they skip would cost less than a real player, which is
     * the figure this stage exists to measure. Null when switched off in config.
     */
    private final String metadataKey;

    private String metadataKeyFromConfig() {
        final String key = this.plugin.getConfig().getString("benchmark.bots.metadata-key", "CatalystBot");

        return key == null || key.isBlank() ? null : key.trim();
    }

    /** LOWEST, so other plugins' own join handlers already see the tag. Proven bots only. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinTag(PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        if (this.metadataKey == null || this.world == null || !this.isVerifiedBot(player)) return;
        player.setMetadata(this.metadataKey, new FixedMetadataValue(this.plugin, true));
        this.tagged.incrementAndGet();
    }

    /**
     * MONITOR, so other plugins' quit handlers can still read the tag first. Bukkit keeps
     * player metadata after logout, so it has to be taken off here or it would linger.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitUntag(PlayerQuitEvent event) {
        this.untag(event.getPlayer());
    }

    private void untag(Player player) {
        if (this.metadataKey != null) player.removeMetadata(this.metadataKey, this.plugin);
    }

    /** Players currently carrying this stage's tag; for the report and to prove it is removed. */
    int taggedOnline() {
        if (this.metadataKey == null) return 0;
        int n = 0;

        for (final Player p : Bukkit.getOnlinePlayers())
            for (final MetadataValue v : p.getMetadata(this.metadataKey))
                if (v.getOwningPlugin() == this.plugin) n++;

        return n;
    }

    private final AtomicInteger tagged = new AtomicInteger();

    /** The key, or null when off. */
    public String metadataKey() { return this.metadataKey; }

    /** How many bots were tagged during the stage. */
    public int taggedCount() { return this.tagged.get(); }

    private final AtomicInteger skinned = new AtomicInteger();

    /** Counts the bots whose live profile really carries a skin, which proves it took. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinSkin(PlayerJoinEvent event) {
        if (this.skins == null || this.world == null || !this.isVerifiedBot(event.getPlayer())) return;

        try {
            if (PaperSkins.wearsSkin(event.getPlayer()))
                this.skinned.incrementAndGet();
        } catch (LinkageError notPaper) {
            // Skins are Paper-only; nothing to count.
        }
    }

    /** How many bots wore a fetched skin, or -1 when no skins were set up. */
    public int skinnedCount() { return this.skins == null ? -1 : this.skinned.get(); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (this.verified.contains(event.getPlayer().getName())) this.noteWorld(event.getPlayer());
    }

    /**
     * The saves of every world a bot was actually in during this stage, recorded as it goes
     * rather than assumed, so its files are looked for exactly there.
     */
    private final Set<File> enteredSaves = ConcurrentHashMap.newKeySet();

    private void noteWorld(Player player) {
        this.enteredSaves.add(WorldPaths.saveRootOf(player.getWorld()));
    }

    /** The saves a bot's file was seen written to during this stage (see locateSave). */
    private final Set<File> recordedSaves = ConcurrentHashMap.newKeySet();

    /**
     * Where to look for a bot's fresh file: every save a bot was seen in, plus every loaded
     * save, because the server writes a player's file into the main save whichever world
     * they stand in, and Bukkit offers no reliable way to tell which save is the main one.
     */
    private Set<File> searchSaves() {
        final Set<File> saves = new LinkedHashSet<>(this.enteredSaves);
        saves.addAll(WorldPaths.saveRoots());

        return saves;
    }

    /**
     * Where this stage's bot files are deleted: the saves they were seen written to. Only
     * when none was seen yet (a bot gone within its first tick) does it fall back to searching,
     * which is still safe because only files written during this stage, under a proven bot's
     * UUID, are deleted (see deleteData).
     */
    private Set<File> cleanupSaves() {
        return this.recordedSaves.isEmpty() ? this.searchSaves() : Set.copyOf(this.recordedSaves);
    }

    /** When the stage launched the bots; a bot's files are never older than this. */
    private volatile long startedAt;

    // The final figures, kept once the process handle is let go of after the stage.
    private int finalPeak, finalChunks;
    private String finalClient = "";

    public int connected() { return this.process == null ? 0 : this.process.connected(); }
    public int chunks() { return this.process == null ? this.finalChunks : this.process.chunks(); }
    public int peakConnected() { return this.process == null ? this.finalPeak : this.process.peakConnected(); }

    /** Why fewer bots than asked stayed on, or null if they all did. */
    public String shortfall() {
        if (this.process == null) return "the bot process did not start";
        final int on = this.process.connected();

        if (on >= this.count) return null;
        final List<String> left = this.process.departures();
        final String why = !left.isEmpty() ? left.get(0)
                : !this.process.isAlive() ? "bot process exited with code " + this.process.exitCode()
                  + " (see plugins/" + Branding.name() + "/bots/bot-process.log)"
                : "no reason given";

        return on + " of " + this.count + " bots were connected: " + why;
    }

    public String client() { return this.process == null ? this.finalClient : this.process.client(); }

    /**
     * Each bot's data goes the moment it leaves - including one kicked mid-stage - so a
     * rerun never finds a "player who has played here before". The server writes the data
     * while handling the quit, after this event, so the delete waits two ticks.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        final String name = event.getPlayer().getName();

        // Checked directly, not through isVerifiedBot: the stage may already be releasing.
        if (!this.verified.contains(name) || this.yielded.contains(name)) return;
        this.noteWorld(event.getPlayer());
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (Bukkit.getPlayerExact(name) == null) deleteData(name, this.cleanupSaves(), this.startedAt);
        }, 2L);
    }

    /**
     * Asks the bots to leave. Listeners stay registered until they have, so every quit is
     * seen and pruned; then a final sweep catches anything missed.
     */
    public void stop() {
        // From here on no bot is let past a login check or protected from a kick.
        this.releasing = true;
        this.stopSteering();

        if (this.process != null) this.process.requestStop();
        this.plugin.runDelayed(() -> this.plugin.runGlobal(() -> {
            // Keep the bot process's log when it went wrong; it is what explains why. That is a
            // process that crashed or would not stop, but also one that exited cleanly having
            // never connected all the bots asked for - the login timeouts that explain the
            // shortfall are only in this log, and are gone once it is deleted.
            final boolean shortfall = this.process != null && this.process.peakConnected() < this.count;
            final boolean failed = this.process != null
                    && (this.process.isAlive() || this.process.exitCode() != 0 || shortfall);

            // No bot may log in after the sweep below: it would be tracked by nothing and its
            // file would stay for good. So a process that ignored the stop request goes now,
            // not when the reaper gets to it; any bot it had on is then swept like the rest.
            if (this.process != null && this.process.isAlive()) {
                this.plugin.getLogger().warning("Bot process was still running at cleanup; killing it.");
                this.process.kill();
            }
            this.unregister();

            if (this.recordedSaves.isEmpty() && !this.verified.isEmpty())
                this.plugin.getLogger().info("No bot's save was pinned down, so their files were looked for in"
                        + " every loaded save, deleting only those written during the stage.");
            removeLeftovers(this.plugin, this.cleanupSaves(), Set.copyOf(this.verified), this.startedAt);
            this.enteredSaves.clear();
            this.recordedSaves.clear();
            cleanWorkFiles(this.plugin, failed);

            // Nothing of this run stays in memory: the process handle and who was verified.
            if (this.process != null) {
                this.finalPeak = this.process.peakConnected();
                this.finalChunks = this.process.chunks();
                this.finalClient = this.process.client();
            }
            this.process = null;
            this.verified.clear();
        }), 3);
    }

    /**
     * Removes what the bot process wrote into plugins/Catalyst/bots: Via's generated config
     * (bots/via), and the process log unless {@code keepLog}. The downloaded libraries stay,
     * so the next run does not fetch them again.
     */
    public static void cleanWorkFiles(Catalyst plugin, boolean keepLog) {
        final File bots = new File(plugin.getDataFolder(), "bots");
        deleteTree(new File(bots, "via"));

        if (!keepLog) new File(bots, "bot-process.log").delete();
    }

    private static void deleteTree(File dir) {
        if (!dir.exists()) return;

        try (final Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // Held open (Windows) by a process still exiting; the next start clears it.
        }
    }

    /** Plugin shutdown: no time for a graceful exit. The startup sweep prunes their data. */
    public void kill() {
        this.releasing = true;
        this.unregister();

        if (this.process != null) this.process.kill();
    }

    /** Paper's spawn event, else Spigot's; plain CraftBukkit has neither and uses the join teleport. */
    private Listener spawnListener() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent");
            return new PaperBotSpawnListener(this::isVerifiedName, this::spawnFor);
        } catch (ClassNotFoundException notPaper) {
            // Fall through to Spigot.
        }

        try {
            Class.forName("org.spigotmc.event.player.PlayerSpawnLocationEvent");
            return new SpigotBotSpawnListener(this::isVerifiedName, this::spawnFor);
        } catch (ClassNotFoundException notSpigot) {
            return null;
        }
    }

    private void stopSteering() {
        if (this.roamTask != null) this.roamTask.cancel();
        this.roamTask = null;
    }

    private void unregister() {
        // Once unregistered the quit handler no longer runs, so take the tag off anyone still on.
        for (final Player p : Bukkit.getOnlinePlayers()) this.untag(p);
        this.stopSteering();

        if (this.roamGuard != null) HandlerList.unregisterAll(this.roamGuard);

        if (this.vibrationGuard != null) HandlerList.unregisterAll(this.vibrationGuard);
        this.roamGuard = this.vibrationGuard = null;
        HandlerList.unregisterAll(this);

        if (this.spawnListener != null) HandlerList.unregisterAll(this.spawnListener);
        this.spawnListener = null;

        if (this.loginListener != null) HandlerList.unregisterAll(this.loginListener);
        this.loginListener = null;

        // No connection may skip authentication once the stage is over.
        if (this.bypass != null) {
            this.bypass.uninstall();
            this.bypass = null;
        }
    }

    /**
     * Kicks any bot still online and deletes the player data bots leave behind. Only
     * touches names listed in the marker, i.e. bots Catalyst itself started. Also run at
     * startup, for a server that died mid-stage.
     */
    public static void removeLeftovers(Catalyst plugin) {
        final SaveRecord record = readRecord(plugin);

        if (record == null) {
            // Left by a build that kept no record: there is no run to date the files from, so
            // any file under a listed bot's UUID goes. The name was never given up, so nobody
            // else has used it.
            removeLeftovers(plugin, WorldPaths.saveRoots(), Set.of(), 0L);
        } else if (!record.saves().isEmpty()) {
            removeLeftovers(plugin, record.saves(), Set.of(), record.startedAt());
        } else {
            // The server stopped before any bot's save was pinned down.
            if (!readMarker(plugin).isEmpty())
                plugin.getLogger().info("The last bot run recorded no save, so its bots' files were looked for"
                        + " in every loaded save, deleting only those written since it started.");
            removeLeftovers(plugin, WorldPaths.saveRoots(), Set.of(), record.startedAt());
        }
    }

    /**
     * @param provenBots names that presented this stage's token; empty at startup, when
     *                   nothing can be proven, so nobody online is kicked then
     * @param since      only files written at or after this time are deleted; 0 for any
     */
    private static void removeLeftovers(Catalyst plugin, Collection<File> saves, Set<String> provenBots, long since) {
        final Set<String> ours = readMarker(plugin);

        if (ours.isEmpty()) {
            clearRecord(plugin);
            return;
        }

        for (final String name : ours) {
            final Player online = Bukkit.getPlayerExact(name);

            if (online != null && !provenBots.contains(name)) {
                // Online under a bot's name without being a proven bot: someone else. Their
                // file is theirs now; leave it, and stop tracking the name.
                unmark(plugin, name);
                plugin.getLogger().warning(name + " is online but is not one of " + Branding.name()
                        + "'s bots, so its player data was left alone.");
                continue;
            }

            if (online != null) {
                // A straggling bot: its file is written as it leaves, so delete a moment after. Through
                // the scheduler adapter (off-thread file work), which also keeps it safe on Folia.
                online.kickPlayer(Branding.name() + " benchmark finished");
                plugin.runDelayed(() -> deleteData(name, saves, since), 1);
            } else {
                deleteData(name, saves, since);
            }
            unmark(plugin, name);
        }

        // Every listed bot is dealt with, so where their files went no longer matters.
        if (readMarker(plugin).isEmpty()) clearRecord(plugin);
    }

    /**
     * Everything the server keeps about one bot - position and inventory, advancements,
     * stats - in each of the given saves. Only that bot's own UUID files are touched, and
     * only ones written since {@code since}, so a file the stage did not produce is kept.
     */
    private static void deleteData(String name, Collection<File> saves, long since) {
        final String uuid = offlineUuid(name).toString();

        // Two seconds' slack: some filesystems store modification times that coarsely.
        final long cutoff = since <= 0 ? Long.MIN_VALUE : since - 2000;

        for (final File save : saves)
            for (final String path : dataPaths(uuid)) {
                final File file = new File(save, path);

                if (file.isFile() && file.lastModified() >= cutoff) file.delete();
            }
    }

    /**
     * Where a player's files live, relative to the main world folder. Minecraft 26 moved them
     * under players/; both layouts are covered, since the old one is still what 1.21 and
     * earlier use.
     */
    static List<String> dataPaths(String uuid) {
        return List.of(
                "playerdata/" + uuid + ".dat", "playerdata/" + uuid + ".dat_old",
                "advancements/" + uuid + ".json", "stats/" + uuid + ".json",
                "players/data/" + uuid + ".dat", "players/data/" + uuid + ".dat_old",
                "players/advancements/" + uuid + ".json", "players/stats/" + uuid + ".json");
    }

    /** Offline-mode servers derive a player's UUID from their name alone. */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * How the bots get past proxy forwarding, as the line BotMain reads first: "FORWARD NONE",
     * "FORWARD BUNGEE", or "FORWARD VELOCITY <base64 secret>". {@code problem} is set when
     * forwarding is on but cannot be supplied.
     */
    record ForwardingPlan(String line, String label, String problem) {}

    @SuppressWarnings("removal") // Paper plans to drop Bukkit.spigot(); on Spigot it is the only way in
    static ForwardingPlan forwardingPlan() {
        // Velocity first: Paper refuses to start with both on, and Velocity is the stricter one.
        for (final String file : List.of("config/paper-global.yml", "paper.yml")) {
            final File f = new File(PlatformDetector.serverRoot(), file);

            if (!f.isFile()) continue;
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
            final boolean on = yaml.getBoolean("proxies.velocity.enabled") || yaml.getBoolean("settings.velocity-support.enabled");

            if (!on) continue;
            final String secret = yaml.getString("proxies.velocity.secret", yaml.getString("settings.velocity-support.secret", ""));

            if (secret == null || secret.isBlank())
                return new ForwardingPlan(null, null, "Velocity forwarding is on but no secret is set in " + file + ".");
            return new ForwardingPlan("FORWARD VELOCITY "
                    + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)),
                    "Velocity forwarding", null);
        }

        try {
            if (Bukkit.spigot().getConfig().getBoolean("settings.bungeecord"))
                return new ForwardingPlan("FORWARD BUNGEE", "BungeeCord forwarding", null);
        } catch (Throwable ignored) {
            // Plain CraftBukkit has no Spigot config, and no proxy support.
        }

        return new ForwardingPlan("FORWARD NONE", null, null);
    }

    private static File marker(Catalyst plugin) {
        return new File(plugin.getDataFolder(), "bots/bot-names.txt");
    }

    /** Login checks run on several threads; the list file is only ever touched under this. */
    private static final Object MARKER_LOCK = new Object();

    private void writeMarker() throws IOException {
        synchronized (MARKER_LOCK) {
            final File f = marker(this.plugin);
            Files.createDirectories(f.getParentFile().toPath());
            final Set<String> all = readMarker(this.plugin);
            all.addAll(this.names);
            Files.write(f.toPath(), new ArrayList<>(all), StandardCharsets.UTF_8);
        }
    }

    /** Takes one name off the list; deletes the file once the list is empty. */
    private static void unmark(Catalyst plugin, String name) {
        synchronized (MARKER_LOCK) {
            final File f = marker(plugin);
            final Set<String> all = readMarker(plugin);

            if (!all.remove(name)) return;

            try {
                if (all.isEmpty()) Files.deleteIfExists(f.toPath());
                else Files.write(f.toPath(), new ArrayList<>(all), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // Failing to shrink the list must not leave the name marked for deletion.
                plugin.getLogger().warning("Could not update " + f + ": " + e.getMessage()
                        + ". Delete it by hand so " + name + "'s data is not cleaned up.");
            }
        }
    }

    private static Set<String> readMarker(Catalyst plugin) {
        synchronized (MARKER_LOCK) {
            final File f = marker(plugin);
            final Set<String> set = new HashSet<>();

            if (!f.isFile()) return set;

            try {
                for (final String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
                    // Any bot name a run of Catalyst's wrote, whatever prefix it had then.
                    if (line.matches("[A-Za-z0-9_]{1,13}\\d{1,2}")) set.add(line);
            } catch (IOException ignored) {
                // Treated as empty: stricter, never looser.
            }
            return set;
        }
    }

    /**
     * Which saves bot files went to, kept next to the name list so it survives a crash.
     * Lines are "started <millis>" and "save <folder>".
     */
    private static File saveRecord(Catalyst plugin) {
        return new File(plugin.getDataFolder(), "bots/bot-saves.txt");
    }

    /** When the run began, and the saves its bots' files were seen written to. */
    record SaveRecord(long startedAt, Set<File> saves) {}

    private static void recordStart(Catalyst plugin, long startedAt) {
        synchronized (MARKER_LOCK) {
            final SaveRecord old = readRecord(plugin);

            // A record an earlier run never cleaned up keeps its start, so its files still qualify.
            final long start = old == null ? startedAt : Math.min(old.startedAt(), startedAt);
            writeRecord(plugin, new SaveRecord(start, old == null ? Set.of() : old.saves()));
        }
    }

    private static void recordSave(Catalyst plugin, File save) {
        synchronized (MARKER_LOCK) {
            final SaveRecord old = readRecord(plugin);

            if (old == null) return;
            final Set<File> saves = new LinkedHashSet<>(old.saves());

            if (saves.add(normal(save))) writeRecord(plugin, new SaveRecord(old.startedAt(), saves));
        }
    }

    private static void clearRecord(Catalyst plugin) {
        synchronized (MARKER_LOCK) {
            try {
                Files.deleteIfExists(saveRecord(plugin).toPath());
            } catch (IOException ignored) {
                // A stale record only narrows where the next cleanup looks, to real saves.
            }
        }
    }

    private static void writeRecord(Catalyst plugin, SaveRecord record) {
        final List<String> lines = new ArrayList<>();
        lines.add("started " + record.startedAt());

        for (final File save : record.saves()) lines.add("save " + save.getAbsolutePath());
        final File f = saveRecord(plugin);

        try {
            Files.createDirectories(f.getParentFile().toPath());
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Without the record, cleanup falls back to searching every save, time-filtered.
            plugin.getLogger().warning("Could not write " + f + ": " + e.getMessage());
        }
    }

    /**
     * Null when there is no usable record. A listed folder is only accepted inside the server
     * or its world container, so an edited file cannot point deletion anywhere else.
     */
    private static SaveRecord readRecord(Catalyst plugin) {
        synchronized (MARKER_LOCK) {
            final File f = saveRecord(plugin);

            if (!f.isFile()) return null;
            long started = -1;
            final Set<File> saves = new LinkedHashSet<>();

            try {
                for (final String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (line.startsWith("started ")) started = Long.parseLong(line.substring(8).trim());
                    else if (line.startsWith("save ")) {
                        final File save = new File(line.substring(5).trim());

                        if (save.isDirectory() && insideServer(save)) saves.add(normal(save));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                return null;
            }
            return started > 0 ? new SaveRecord(started, saves) : null;
        }
    }

    private static File normal(File dir) {
        return dir.toPath().toAbsolutePath().normalize().toFile();
    }

    private static boolean insideServer(File dir) {
        try {
            final String path = dir.getCanonicalPath() + File.separator;

            for (final File root : List.of(PlatformDetector.serverRoot(), Bukkit.getWorldContainer()))
                if (path.startsWith(root.getCanonicalPath() + File.separator)) return true;
        } catch (IOException ignored) {
            // Unresolvable: not accepted.
        }

        return false;
    }
}

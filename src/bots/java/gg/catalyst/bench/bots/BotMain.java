// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundExplodePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bot client, run by BotProcess in its own JVM so the bots' CPU is never counted as server
 * load. Bots join, answer what a vanilla client must to keep receiving the world, and walk lanes of
 * their own - or, in roam mode, routes the server plans and sends on stdin as "PATH name x,y,z,jump"
 * (only the server knows the blocks), walking them with gravity and the odd idle jump. Progress goes
 * to stdout as lines the parent parses.
 *
 * Args: port, count, seconds, server version, name prefix, optional "roam". Then two stdin lines:
 * proxy forwarding (see Forwarding) and the identity token. No address is passed - bots only ever
 * reach this machine.
 */
public final class BotMain {
    private static final String LOOPBACK = "127.0.0.1";
    /** Absolute ceiling, matching BotProcess.MAX_BOTS; the everyday limit is enforced server-side. */
    static final int MAX_BOTS = 200;
    private static final int MAX_SECONDS = 600;
    /** Walking pace in blocks per tick; a vanilla player walks at about 0.22. */
    private static final double STEP = 0.2;
    /** How long a bot stands where it was placed before it starts walking. */
    private static final long STAND_STILL_MILLIS = 5000;
    /** Vanilla's jump: upward speed on take-off, then gravity and drag every tick. */
    private static final double JUMP = 0.42, GRAVITY = 0.08, DRAG = 0.98;
    /** Higher than this, a step needs a jump; up to it, a player just walks up (stairs, slabs). */
    private static final double STEP_UP = 0.6;
    /** Chance each tick that a roaming bot jumps for no reason. */
    private static final double IDLE_JUMP = 0.02;
    /** How close to a block's middle the body's edge reaches it: half a block plus half the 0.6 width. */
    private static final double EDGE = 0.81;

    private static final class Bot {
        final String name;
        volatile Session session;
        volatile double x, y, z;
        volatile boolean placed;

        /** When the bot was first placed; it stands still for a moment after that. */
        volatile long placedAt;

        /** When the first chunk arrived; 0 until then. The bot only walks a while after it. */
        volatile long firstChunkAt;
        final AtomicInteger chunks = new AtomicInteger();

        // Roaming, all under the bot's lock: the route left to walk, the height of the ground
        // under the bot, and its fall.
        final ArrayDeque<double[]> route = new ArrayDeque<>();
        double floor, vx, vy, vz;
        boolean onGround = true;

        /** Whether there is headroom to jump where the bot is; false until the server says so. */
        boolean roomToJump;
        float yaw;

        // What the server was last told, under the bot's lock: like a vanilla client, a bot only
        // sends what changed, and its position at least once a second.
        double sentX, sentY, sentZ;
        float sentYaw;
        boolean sentGround = true;
        int sinceSent;

        /** The bot's own entity, from the login packet, so motion meant for it is recognised. */
        volatile int entityId = Integer.MIN_VALUE;
        Bot(String name) { this.name = name; }
        boolean connected() { return this.placed && this.session != null && this.session.isConnected(); }
    }

    private static final AtomicBoolean stopping = new AtomicBoolean();
    private static Bot[] bots = new Bot[0];

    private static boolean roam;
    private static final Random random = new Random();

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && !(args.length == 6 && args[5].equals("roam")))
            fail("usage: <port> <count> <seconds> <server version> <name prefix> [roam]");
        roam = args.length == 6;

        // Checked here as well as by the server: the prefix and a number must make a valid name.
        final String prefix = args[4];

        if (!prefix.matches("[A-Za-z0-9_]{0,12}[A-Za-z_]")) fail("bad name prefix: " + prefix);
        final int port = parse(args[0], 1, 65535, "port");
        final int count = parse(args[1], 1, MAX_BOTS, "count");
        final int seconds = parse(args[2], 1, MAX_SECONDS, "seconds");
        final String serverVersion = args[3];

        if (!serverVersion.matches("[0-9][0-9.]{0,15}")) fail("bad server version: " + serverVersion);

        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "bot-ticker");
            t.setDaemon(true);
            return t;
        });

        // Whatever happens, never outlive the stage: stop at the deadline, and halt hard
        // shortly after in case a clean stop hangs.
        ticker.schedule(BotMain::stop, seconds, TimeUnit.SECONDS);
        ticker.schedule(() -> Runtime.getRuntime().halt(3), seconds + 15L, TimeUnit.SECONDS);

        // Two lines on stdin: how to get past proxy forwarding, then the stage's identity token.
        // They come this way, not as arguments, because neither the Velocity secret nor the
        // token may show up in the process list.
        final BufferedReader parent = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final Forwarding forwarding = Forwarding.parse(parent.readLine());
        final String identityHost = readIdentity(parent.readLine());
        watchParent(parent);

        final ViaBridge via = ViaBridge.forServer(serverVersion, new File("via"));
        emit("CLIENT " + ViaBridge.nativeVersion() + (via == null ? "" : " VIA " + via.target()));

        bots = new Bot[count];
        final InetSocketAddress address = new InetSocketAddress(LOOPBACK, port);

        for (int i = 0; i < count && !stopping.get(); i++) {
            final Bot bot = bots[i] = new Bot(prefix + (i + 1));
            final ClientNetworkSession session = new BotSession(address, new MinecraftProtocol(bot.name), via);
            bot.session = session;

            // Identity first, then forwarding: the handshake's host becomes the stage token, and
            // forwarding (if any) appends its data after it.
            session.addListener(identityListener(identityHost));
            final SessionAdapter forward = forwarding.listenerFor(bot.name);

            if (forward != null) session.addListener(forward);
            session.addListener(listener(bot));
            session.connect(false);

            // Staggered, as real joins are; a burst of logins is a different benchmark.
            Thread.sleep(150);
        }

        // Each bot walks steadily towards +x along its own lane (the server spawns each in a
        // separate lane, wider than its view), so new terrain keeps loading and no two bots
        // ever load the same chunks.
        ticker.scheduleAtFixedRate(() -> {
            for (final Bot bot : bots) {
                if (bot == null || !bot.connected()) continue;

                // A few seconds on the spot first, and never before the world around the bot has
                // arrived. A server ignores a player's moves until it ticks them, which it only
                // does once their chunk is ready; a busy Spigot can take over ten seconds. Walking
                // blind meanwhile ends in one long jump, rejected as "moved too quickly". A server
                // that moves players right after they join (CraftBukkit has no spawn event to place
                // them) is covered by the same wait. The stage settles for longer anyway.
                if (System.currentTimeMillis() - bot.placedAt < STAND_STILL_MILLIS) continue;

                if (bot.firstChunkAt == 0 || System.currentTimeMillis() - bot.firstChunkAt < 1000) continue;

                // Under the bot's lock, like the teleport below: otherwise a teleport landing
                // between reading and writing the position is overwritten by the old one plus a
                // step, and the server kicks the bot back for "moving too quickly".
                synchronized (bot) {
                    if (roam) roam(bot);
                    else {
                        fall(bot);
                        drift(bot);
                        bot.x += STEP;
                    }
                    step(bot);
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);

        ticker.scheduleAtFixedRate(() -> emit(status("STATUS")), 1, 1, TimeUnit.SECONDS);

        // Everything else happens on Netty and ticker threads until stop() exits.
        Thread.currentThread().join();
    }

    /**
     * One client tick: the bot's position, then "tick end", as a vanilla client sends at the
     * end of every tick since 1.21.2. Servers from 26.3 depend on it: a player may report one
     * position per client tick, and only tick end starts the next, so a second position
     * without it is kicked as invalid movement. Sent as a pair under the bot's lock, since the
     * walker and a server teleport can both move the bot from different threads.
     */
    private static void step(Bot bot) {
        synchronized (bot) {
            // Vanilla's rules (LocalPlayer.sendPosition): position when it moved or every 20
            // ticks, rotation when it turned, ground state alone when only that changed, else
            // nothing but tick end. Sending everything every tick would make a standing bot
            // cost more packet handling than a standing player.
            final double dx = bot.x - bot.sentX, dy = bot.y - bot.sentY, dz = bot.z - bot.sentZ;
            final boolean moved = dx * dx + dy * dy + dz * dz > 4.0E-8 || ++bot.sinceSent >= 20;
            final boolean turned = roam && bot.yaw != bot.sentYaw;

            if (moved && turned) bot.session.send(new ServerboundMovePlayerPosRotPacket(bot.onGround, false, bot.x, bot.y, bot.z, bot.yaw, 0f));
            else if (moved) bot.session.send(new ServerboundMovePlayerPosPacket(bot.onGround, false, bot.x, bot.y, bot.z));
            else if (turned) bot.session.send(new ServerboundMovePlayerRotPacket(bot.onGround, false, bot.yaw, 0f));
            else if (bot.onGround != bot.sentGround) bot.session.send(new ServerboundMovePlayerStatusOnlyPacket(bot.onGround, false));

            if (moved) {
                bot.sentX = bot.x;
                bot.sentY = bot.y;
                bot.sentZ = bot.z;
                bot.sinceSent = 0;
            }

            if (turned) bot.sentYaw = bot.yaw;
            bot.sentGround = bot.onGround;
            bot.session.send(ServerboundClientTickEndPacket.INSTANCE);
        }
    }

    /**
     * One tick of walking a route, under the bot's lock: falling or jumping first, then a
     * walking step towards the next waypoint. A climb of more than a stair is jumped, and the
     * bot waits at the foot until it is high enough, so it never moves into the block it is
     * climbing - the server would put it back. A drop is walked off and fallen down.
     */
    private static void roam(Bot bot) {
        fall(bot);
        drift(bot);
        final double[] next = bot.route.peek();

        if (next == null) {
            if (bot.onGround && bot.roomToJump && random.nextDouble() < IDLE_JUMP) jump(bot);
            return;
        }
        final double dx = next[0] - bot.x, dz = next[2] - bot.z, distance = Math.hypot(dx, dz);
        final double climb = next[1] - bot.floor;

        if (climb > STEP_UP && bot.onGround) jump(bot);
        else if (climb <= STEP_UP && bot.onGround && bot.roomToJump && random.nextDouble() < IDLE_JUMP / 2) jump(bot);

        if (distance > 1e-6) bot.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // The body is 0.6 wide, so it touches the next block once within 0.8 of its middle.
        // Climbing, it stops short of that until high enough to clear the block: the server
        // puts a player back the moment their body is inside one, which is what walking into
        // a block's side at its foot would be.
        double move = Math.min(STEP, distance);

        if (climb > STEP_UP && bot.y < next[1]) move = Math.min(move, Math.max(0, distance - EDGE));

        if (distance > 1e-6) {
            bot.x += dx / distance * move;
            bot.z += dz / distance * move;
        }
        final double left = distance - move;

        if (climb > 0 && left < EDGE) {
            // Over the higher block now: it is the ground to land on, and a stair or slab is
            // stepped up as soon as the body reaches it, as the server would.
            if (climb > STEP_UP && bot.y >= next[1]) bot.floor = next[1];
            else if (climb <= STEP_UP && bot.onGround) bot.y = bot.floor = next[1];
        }

        if (distance > STEP) return;
        bot.route.poll();
        bot.roomToJump = next[3] > 0;

        if (bot.route.isEmpty()) emit("IDLE " + bot.name);

        // Arrived over the next block: its ground is now the one to stand or land on.
        bot.floor = next[1];

        if (climb > 0 && climb <= STEP_UP && bot.onGround) bot.y = next[1];
        else if (climb < 0 && bot.onGround) {
            if (-climb <= STEP_UP) bot.y = next[1];
            else {
                bot.onGround = false;
                bot.vy = 0;
            }
        }
    }

    private static void jump(Bot bot) {
        bot.onGround = false;
        bot.vy = JUMP;
    }

    /** Up or down under gravity and drag, until back on the ground the bot was over. */
    private static void fall(Bot bot) {
        if (bot.onGround) return;
        bot.y += bot.vy;
        bot.vy = (bot.vy - GRAVITY) * DRAG;

        if (bot.vy < 0 && bot.y <= bot.floor) {
            bot.y = bot.floor;
            bot.vy = 0;
            bot.onGround = true;
        }
    }

    /** Vanilla's slowing of sideways motion each tick: block friction 0.6 times 0.91 on the ground, 0.91 in the air. */
    private static final double GROUND_FRICTION = 0.546, AIR_DRAG = 0.91;

    /** Knockback the server gave the bot, carried on and slowed each tick as a vanilla client does. */
    private static void drift(Bot bot) {
        if (bot.vx == 0 && bot.vz == 0) return;
        bot.x += bot.vx;
        bot.z += bot.vz;
        final double slow = bot.onGround ? GROUND_FRICTION : AIR_DRAG;
        bot.vx = Math.abs(bot.vx * slow) < 0.003 ? 0 : bot.vx * slow;
        bot.vz = Math.abs(bot.vz * slow) < 0.003 ? 0 : bot.vz * slow;
    }

    /**
     * Motion the server gives the bot - a hit, an explosion, a push - replaces or adds to what
     * it has, as on a vanilla client. A roaming bot knocked off its route asks for a new one
     * from where it lands, rather than walking on towards blocks now in the way.
     */
    private static void knock(Bot bot, double x, double y, double z, boolean replace) {
        synchronized (bot) {
            if (replace) {
                bot.vx = x;
                bot.vy = y;
                bot.vz = z;
            } else {
                bot.vx += x;
                bot.vy += y;
                bot.vz += z;
            }

            if (bot.vy > 0) bot.onGround = false;

            if (roam && !bot.route.isEmpty()) {
                bot.route.clear();
                emit("IDLE " + bot.name);
            }
        }
    }

    /** "PATH name x,y,z;x,y,z;..." from the server: that bot's new route, replacing any left. */
    private static void takeRoute(String line) {
        final String[] parts = line.split(" ", 3);

        if (parts.length < 3) return;

        for (final Bot bot : bots) {
            if (bot == null || !bot.name.equals(parts[1])) continue;
            final ArrayDeque<double[]> route = new ArrayDeque<>();

            for (final String point : parts[2].split(";")) {
                final String[] xyz = point.split(",");

                if (xyz.length != 4) return;

                try {
                    route.add(new double[]{Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                            Double.parseDouble(xyz[3])});
                } catch (NumberFormatException malformed) {
                    return;
                }
            }
            synchronized (bot) {
                bot.route.clear();
                bot.route.addAll(route);
            }
            return;
        }
    }

    private static SessionAdapter listener(Bot bot) {
        return new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundPlayerPositionPacket pos) {
                    synchronized (bot) {
                        bot.x = pos.getPosition().getX();
                        bot.y = pos.getPosition().getY();
                        bot.z = pos.getPosition().getZ();

                        // Put somewhere by the server: stand there, and wait for a new route from it.
                        bot.floor = bot.y;
                        bot.vx = bot.vy = bot.vz = 0;
                        bot.onGround = true;

                        if (roam && !bot.route.isEmpty()) {
                            bot.route.clear();
                            emit("IDLE " + bot.name);
                        }
                        s.send(new ServerboundAcceptTeleportationPacket(pos.getId()));

                        // A vanilla client follows a teleport confirmation with its full position
                        // and rotation, and 26.3 relies on it: ViaBackwards holds the confirmation
                        // back and merges it into that packet, since 26.3 confirms a teleport with
                        // the position in it. With a position-only packet the confirmation never
                        // reaches the server, which then ignores every move the bot makes.
                        s.send(new ServerboundMovePlayerPosRotPacket(true, false, bot.x, bot.y, bot.z,
                                pos.getYRot(), pos.getXRot()));
                        s.send(ServerboundClientTickEndPacket.INSTANCE);
                        bot.sentX = bot.x;
                        bot.sentY = bot.y;
                        bot.sentZ = bot.z;
                        bot.yaw = bot.sentYaw = pos.getYRot();
                        bot.sentGround = true;
                        bot.sinceSent = 0;
                    }

                    if (!bot.placed) {
                        bot.placedAt = System.currentTimeMillis();
                        bot.placed = true;
                        s.send(ServerboundPlayerLoadedPacket.INSTANCE);
                        emit("JOINED " + bot.name);
                    }
                } else if (packet instanceof ClientboundLoginPacket login) {
                    bot.entityId = login.getEntityId();

                    // A vanilla client sends its brand on minecraft:brand as it enters play;
                    // without it the server logs the bot as brand=unknown, which gives it away.
                    // Sending "vanilla" makes the bots read as ordinary clients.
                    s.send(new ServerboundCustomPayloadPacket(Key.key("minecraft", "brand"), brandPayload("vanilla")));
                } else if (packet instanceof ClientboundSetEntityMotionPacket motion && motion.getEntityId() == bot.entityId) {
                    final Vector3d v = motion.getMovement();
                    knock(bot, v.getX(), v.getY(), v.getZ(), true);
                } else if (packet instanceof ClientboundExplodePacket blast && blast.getPlayerKnockback() != null) {
                    final Vector3d v = blast.getPlayerKnockback();
                    knock(bot, v.getX(), v.getY(), v.getZ(), false);
                } else if (packet instanceof ClientboundLevelChunkWithLightPacket) {
                    if (bot.chunks.incrementAndGet() == 1) bot.firstChunkAt = System.currentTimeMillis();
                } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                    // Without this acknowledgement the server stops streaming chunks.
                    s.send(new ServerboundChunkBatchReceivedPacket(64f));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                if (!stopping.get())
                    emit("LEFT " + bot.name + " " + oneLine(plain(event.getReason())));
            }
        };
    }

    /**
     * The minecraft:brand payload: the brand name as a length-prefixed string. A brand is only
     * ever a few characters, so its VarInt length is always the single byte written here.
     */
    private static byte[] brandPayload(String brand) {
        final byte[] name = brand.getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[name.length + 1];
        out[0] = (byte) name.length;
        System.arraycopy(name, 0, out, 1, name.length);

        return out;
    }

    /**
     * "IDENTITY catalyst-<token>.invalid": the host every bot puts in its handshake, which is
     * how Catalyst on the server tells its own bots from anyone else using a bot's name.
     */
    private static String readIdentity(String line) {
        if (line == null || !line.matches("IDENTITY catalyst-[0-9a-f]{32}\\.invalid"))
            throw new IllegalArgumentException("bad identity line from the parent");

        return line.substring("IDENTITY ".length());
    }

    private static SessionAdapter identityListener(String host) {
        return new SessionAdapter() {
            @Override
            public void packetSending(PacketSendingEvent event) {
                if (event.getPacket() instanceof ClientIntentionPacket intent)
                    event.setPacket(intent.withHostname(host));
            }
        };
    }

    /** Leaves cleanly: disconnects every bot so the server saves and removes them normally. */
    private static void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        emit(status("DONE"));

        for (final Bot bot : bots)
            if (bot != null && bot.session != null && bot.session.isConnected())
                bot.session.disconnect("Catalyst benchmark finished");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            // Exiting anyway.
        }
        System.exit(0);
    }

    /** The parent writes "stop", or closes our stdin by dying; either way, leave. */
    private static void watchParent(BufferedReader parent) {
        final Thread t = new Thread(() -> {
            try (final BufferedReader in = parent) {
                String line;

                while ((line = in.readLine()) != null) {
                    if (line.trim().equals("stop")) break;

                    if (line.startsWith("PATH ")) takeRoute(line);
                }
            } catch (Exception ignored) {
                // Treated as the parent going away.
            }
            stop();
        }, "bot-parent-watch");
        t.setDaemon(true);
        t.start();
    }

    private static String status(String tag) {
        int connected = 0, chunks = 0;

        for (final Bot bot : bots) {
            if (bot == null) continue;

            if (bot.connected()) connected++;
            chunks += bot.chunks.get();
        }

        return tag + " connected=" + connected + " chunks=" + chunks;
    }

    private static synchronized void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }

    /**
     * A disconnect reason as text. The server usually sends a translation key, which the bots
     * have no language files to resolve, so the key itself is shown ("multiplayer.disconnect.
     * duplicate_login" still says what happened) rather than the component's debug form.
     */
    private static String plain(Component c) {
        if (c == null) return "no reason given";
        final StringBuilder out = new StringBuilder();

        if (c instanceof TextComponent t) out.append(t.content());
        else if (c instanceof TranslatableComponent t)
            out.append(t.fallback() != null ? t.fallback() : t.key());

        for (final Component child : c.children()) out.append(plain(child));

        return out.toString();
    }

    private static String oneLine(String s) {
        final String flat = s.replaceAll("\\s+", " ");

        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    private static int parse(String value, int min, int max, String what) {
        try {
            final int n = Integer.parseInt(value);

            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        fail(what + " must be " + min + "-" + max + ", got " + value);

        return -1;
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }

    /** MCProtocolLib's client session, with Via's translators added when the versions differ. */
    private static final class BotSession extends ClientNetworkSession {
        private final ViaBridge via;

        BotSession(InetSocketAddress address, MinecraftProtocol protocol, ViaBridge via) {
            // Packets are handled on the connection's own Netty thread, which keeps each
            // bot's packets in order without a thread per bot.
            super(address, protocol, Runnable::run, null, null);
            this.via = via;
        }

        @Override
        protected ChannelHandler getChannelHandler() {
            final ChannelHandler base = super.getChannelHandler();

            if (this.via == null) return base;
            return new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) {
                    // Adding MCProtocolLib's initializer to a registered channel runs it at
                    // once, so its handlers are all in place before Via inserts its own.
                    channel.pipeline().addLast(base);
                    via.inject(channel);
                }
            };
        }
    }
}

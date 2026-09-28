// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import io.netty.channel.*;
import org.bukkit.Server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Lets this stage's bots - and only them - log in to an <em>online-mode</em> server without
 * Minecraft accounts. The one place Catalyst touches authentication, so the gate is narrow: a
 * connection skips the Mojang check only from loopback, carrying the stage's random token in its
 * handshake (the same proof {@link BotStage#verifyLogin} demands), under one of the stage's bot names.
 *
 * <p>A Netty handler before the server's packet handler spots a passing login handshake, then on the
 * follow-up hello drives Paper's own offline-login path (createOfflineProfile, the pre-login events,
 * startClientVerification) and swallows the hello, so the server never sends an encryption request or
 * calls Mojang. Every other connection authenticates as before.
 *
 * <p>Reaches server internals by reflection; only Paper 1.20.5+ ships them under these names.
 * {@link #supported()} reports whether all were found - when not, {@link BotStage#plan} refuses.
 */
final class OnlineModeBypass {
    // Resolved once against the running server. Null/false means this server is not a
    // Mojang-mapped Paper and the bypass cannot run here.
    private static final Method GET_PACKET_LISTENER;
    private static final Class<?> LOGIN_IMPL;
    private static final Field REQUESTED_USERNAME;
    private static final Field DISABLE_USERNAME_VALIDATION;
    private static final Method CREATE_OFFLINE_PROFILE;
    private static final Method START_CLIENT_VERIFICATION;
    private static final Method CALL_PRE_LOGIN_EVENTS;
    private static final Method DISCONNECT;
    private static final Class<?> INTENTION_PACKET;
    private static final Method INTENTION_HOST;
    private static final Method INTENTION_INTENT;
    private static final Class<?> HELLO_PACKET;
    private static final Method HELLO_NAME;
    private static final boolean SUPPORTED;

    static {
        Method getPacketListener = null, createOffline = null, startVerify = null, callPreLogin = null, disconnect = null;
        Method intentionHost = null, intentionIntent = null, helloName = null;
        Class<?> loginImpl = null, intentionPacket = null, helloPacket = null;
        Field requestedUsername = null, disableValidation = null;
        boolean ok = false;

        try {
            final Class<?> connection = Class.forName("net.minecraft.network.Connection");
            getPacketListener = connection.getMethod("getPacketListener");

            loginImpl = Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl");
            requestedUsername = loginImpl.getField("requestedUsername");
            disableValidation = loginImpl.getField("iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation");
            final Class<?> gameProfile = Class.forName("com.mojang.authlib.GameProfile");
            createOffline = loginImpl.getDeclaredMethod("createOfflineProfile", String.class);
            createOffline.setAccessible(true);
            startVerify = loginImpl.getDeclaredMethod("startClientVerification", gameProfile);
            startVerify.setAccessible(true);
            callPreLogin = loginImpl.getDeclaredMethod("callPlayerPreLoginEvents", gameProfile);
            callPreLogin.setAccessible(true);
            disconnect = loginImpl.getMethod("disconnect", String.class);

            intentionPacket = Class.forName("net.minecraft.network.protocol.handshake.ClientIntentionPacket");
            intentionHost = intentionPacket.getMethod("hostName");
            intentionIntent = intentionPacket.getMethod("intention");

            helloPacket = Class.forName("net.minecraft.network.protocol.login.ServerboundHelloPacket");
            helloName = helloPacket.getMethod("name");
            ok = true;
        } catch (ReflectiveOperationException | LinkageError absent) {
            // Left unsupported; plan() refuses an online-mode stage on this server.
        }
        GET_PACKET_LISTENER = getPacketListener;
        LOGIN_IMPL = loginImpl;
        REQUESTED_USERNAME = requestedUsername;
        DISABLE_USERNAME_VALIDATION = disableValidation;
        CREATE_OFFLINE_PROFILE = createOffline;
        START_CLIENT_VERIFICATION = startVerify;
        CALL_PRE_LOGIN_EVENTS = callPreLogin;
        DISCONNECT = disconnect;
        INTENTION_PACKET = intentionPacket;
        INTENTION_HOST = intentionHost;
        INTENTION_INTENT = intentionIntent;
        HELLO_PACKET = helloPacket;
        HELLO_NAME = helloName;
        SUPPORTED = ok;
    }

    /** Whether this server exposes the internals the bypass needs (Mojang-mapped Paper 1.20.5+). */
    static boolean supported() {
        return SUPPORTED;
    }

    private final Logger log;
    /** (handshake host, remote address) -> may this connection skip authentication. */
    private final BiPredicate<String, InetAddress> gate;
    /** Which login names may use it: this stage's own bots, never an arbitrary name. */
    private final Predicate<String> botName;
    /** Cleared on stop so no connection is ever let past after the stage ends. */
    private volatile boolean active = true;
    private final List<Channel> serverChannels = new ArrayList<>();
    private final Acceptor acceptor = new Acceptor();

    private OnlineModeBypass(Logger log, BiPredicate<String, InetAddress> gate, Predicate<String> botName) {
        this.log = log;
        this.gate = gate;
        this.botName = botName;
    }

    /**
     * Injects the bypass into the running server's listening channels, or returns null if it
     * cannot (not supported here, or the internals could not be reached). {@code gate} decides,
     * per connection, whether it may skip authentication; {@code botName} which login names may.
     */
    static OnlineModeBypass install(Server server, Logger log, BiPredicate<String, InetAddress> gate,
                                    Predicate<String> botName) {
        if (!SUPPORTED) return null;

        try {
            final Object mcServer = server.getClass().getMethod("getServer").invoke(server);
            final Object connectionList = mcServer.getClass().getMethod("getConnection").invoke(mcServer);
            final Field channelsField = connectionList.getClass().getDeclaredField("channels");
            channelsField.setAccessible(true);
            final Object raw = channelsField.get(connectionList);
            final OnlineModeBypass bypass = new OnlineModeBypass(log, gate, botName);
            synchronized (raw) { // Paper guards this list with its own monitor.

                for (final Object o : (List<?>) raw) {
                    final Channel channel = ((ChannelFuture) o).channel();
                    channel.pipeline().addFirst("catalyst-bot-accept", bypass.acceptor);
                    bypass.serverChannels.add(channel);
                }
            }

            if (bypass.serverChannels.isEmpty()) return null;
            return bypass;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.warning("[Catalyst] Could not enable the online-mode bot bypass: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    /** Stops letting any new connection past, and removes the injected acceptor. */
    void uninstall() {
        this.active = false;

        for (final Channel channel : this.serverChannels) {
            try {
                if (channel.pipeline().get("catalyst-bot-accept") != null)
                    channel.pipeline().remove("catalyst-bot-accept");
            } catch (RuntimeException ignored) {
                // Channel already closed; nothing to remove.
            }
        }
        this.serverChannels.clear();
    }

    /**
     * Added to each listening (server) channel. For a server socket, an inbound "read" delivers
     * the freshly accepted child connection, so this adds the per-connection interceptor to it.
     */
    @ChannelHandler.Sharable
    private final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (active && msg instanceof Channel child) {
                // Place the interceptor once the child's pipeline is built (its packet handler
                // present) but before any packet is read, i.e. at channelActive.
                child.pipeline().addLast(new Positioner());
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** One-shot: moves the interceptor in front of the packet handler, then removes itself. */
    private final class Positioner extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            final var pipeline = ctx.pipeline();

            if (pipeline.get("packet_handler") != null && pipeline.get("catalyst-bot-login") == null)
                pipeline.addBefore("packet_handler", "catalyst-bot-login", new LoginInterceptor());

            if (pipeline.context(this) != null) pipeline.remove(this);
            ctx.fireChannelActive();
        }
    }

    /** Sits before the packet handler on one connection, watching its login handshake and hello. */
    private final class LoginInterceptor extends ChannelInboundHandlerAdapter {
        private boolean matched;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // Only the handshake and the login hello matter; once past them (or past a handshake
            // that is not ours), step out so the connection carries nothing extra for its lifetime.
            boolean done = !active;

            try {
                if (active && INTENTION_PACKET.isInstance(msg)) {
                    if (isLogin(INTENTION_INTENT.invoke(msg))) {
                        final String host = (String) INTENTION_HOST.invoke(msg);
                        final InetAddress from = remoteAddress(ctx.channel());
                        this.matched = from != null && gate.test(host, from);
                    }
                    done = !this.matched;
                } else if (this.matched && active && HELLO_PACKET.isInstance(msg)) {
                    done = true;
                    final String name = (String) HELLO_NAME.invoke(msg);

                    // The token proves the connection is ours, but it must still be one of this
                    // stage's bots: never an arbitrary name let in without authentication.
                    if (botName.test(name)) {
                        driveOfflineLogin(ctx.channel(), name);
                        ctx.pipeline().remove(this);
                        return; // Swallow the hello: the server's online-mode path never runs for it.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall through and let the connection authenticate normally.
                done = true;
                log.warning("[Catalyst] Bot login bypass hit an error; letting the connection"
                        + " authenticate normally: " + e.getClass().getSimpleName());
            }

            if (done) ctx.pipeline().remove(this);
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Drives Paper's own offline-login path for one connection, step for step as handleHello
     * does in offline mode: off the network thread, make the offline profile, run the
     * pre-login events, then start verification. The pre-login events matter: skipping them
     * would hide the bots from every plugin that checks logins there (bans, anti-bot, skins).
     */
    private void driveOfflineLogin(Channel channel, String name) throws ReflectiveOperationException {
        final Object connection = channel.pipeline().get("packet_handler");

        if (connection == null) return;
        final Object listener = GET_PACKET_LISTENER.invoke(connection);

        if (!LOGIN_IMPL.isInstance(listener)) return;
        REQUESTED_USERNAME.set(listener, name);
        DISABLE_USERNAME_VALIDATION.setBoolean(listener, true);

        // The pre-login events block (plugins may do I/O in them), so like Paper, not here.
        Thread.ofVirtual().name("catalyst-bot-login").start(() -> {
            try {
                Object profile = CREATE_OFFLINE_PROFILE.invoke(listener, name);
                profile = CALL_PRE_LOGIN_EVENTS.invoke(listener, profile);
                START_CLIENT_VERIFICATION.invoke(listener, profile);
            } catch (ReflectiveOperationException | RuntimeException e) {
                try {
                    DISCONNECT.invoke(listener, "Failed to verify username!");
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // The connection is going anyway.
                }
                this.log.warning("[Catalyst] Bot " + name + " could not finish logging in: " + e.getClass().getSimpleName());
            }
        });
    }

    private static boolean isLogin(Object intent) {
        if (!(intent instanceof Enum<?> e)) return false;

        return e.name().equals("LOGIN") || e.name().equals("TRANSFER");
    }

    private static InetAddress remoteAddress(Channel channel) {
        return channel.remoteAddress() instanceof InetSocketAddress a ? a.getAddress() : null;
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;

/**
 * Lets bots join a proxy backend directly, by supplying the player data the proxy would
 * normally add. Only ever for the bots: loopback, cat_bot_N names, offline-mode UUIDs.
 *
 *  - BungeeCord (legacy) forwarding: the data rides in the handshake's hostname field.
 *  - Velocity (modern) forwarding: the backend asks during login and checks the answer is
 *    signed with the forwarding secret. The secret comes from the server's own config, via
 *    the bot process's stdin, never its command line.
 */
final class Forwarding {
    enum Mode { NONE, BUNGEE, VELOCITY }

    private static final String VELOCITY_CHANNEL = "velocity:player_info";
    /** MODERN_DEFAULT: address, UUID, name, properties. No chat-signing key, which bots do not have. */
    private static final int VELOCITY_VERSION = 1;

    private final Mode mode;
    private final byte[] secret;

    private Forwarding(Mode mode, byte[] secret) {
        this.mode = mode;
        this.secret = secret;
    }

    /** Parses the "FORWARD <mode> [base64 secret]" line BotProcess writes first. */
    static Forwarding parse(String line) {
        if (line == null) throw new IllegalArgumentException("no forwarding line from the parent");
        final String[] parts = line.trim().split(" ");

        if (parts.length < 2 || !parts[0].equals("FORWARD")) throw new IllegalArgumentException("bad forwarding line");
        final Mode mode = Mode.valueOf(parts[1]);

        if (mode != Mode.VELOCITY) return new Forwarding(mode, null);

        if (parts.length != 3) throw new IllegalArgumentException("velocity forwarding needs a secret");

        return new Forwarding(mode, Base64.getDecoder().decode(parts[2]));
    }

    Mode mode() { return this.mode; }

    /** Offline-mode servers derive a player's UUID from their name, and so must the forwarded data. */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Null when nothing needs adding. */
    SessionAdapter listenerFor(String name) {
        return switch (this.mode) {
            case NONE -> null;
            case BUNGEE -> new BungeeListener(name);
            case VELOCITY -> new VelocityListener(name);
        };
    }

    private static final class BungeeListener extends SessionAdapter {
        private final String name;

        BungeeListener(String name) { this.name = name; }

        @Override
        public void packetSending(PacketSendingEvent event) {
            if (event.getPacket() instanceof ClientIntentionPacket intent) {
                final String uuid = offlineUuid(this.name).toString().replace("-", "");

                // Separators as chars: in a string literal "\0127..." would read as the octal escape \012.
                event.setPacket(intent.withHostname(intent.getHostname() + '\0' + "127.0.0.1" + '\0' + uuid));
            }
        }
    }

    private final class VelocityListener extends SessionAdapter {
        private final String name;

        /** Answers this listener wrote. Any other answer to a login query is dropped. */
        private final Set<Packet> ours = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

        VelocityListener(String name) { this.name = name; }

        @Override
        public void packetReceived(Session session, Packet packet) {
            if (!(packet instanceof ClientboundCustomQueryPacket query)) return;
            final ServerboundCustomQueryAnswerPacket answer = query.getChannel().asString().equals(VELOCITY_CHANNEL)
                    ? new ServerboundCustomQueryAnswerPacket(query.getMessageId(), signedPlayerInfo(this.name))

                    // Anything else gets the "not understood" reply a vanilla client gives.
                    : new ServerboundCustomQueryAnswerPacket(query.getMessageId());
            this.ours.add(answer);
            session.send(answer);
        }

        @Override
        public void packetSending(PacketSendingEvent event) {
            // MCProtocolLib answers login queries itself, with "not understood". For the
            // Velocity query that answer would be refused, so every answer is written here.
            if (event.getPacket() instanceof ServerboundCustomQueryAnswerPacket && !this.ours.remove(event.getPacket()))
                event.setCancelled(true);
        }
    }

    /** HMAC-SHA256 signature over the data, then the data, as Velocity itself sends it. */
    byte[] signedPlayerInfo(String name) {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeVarInt(data, VELOCITY_VERSION);
        writeString(data, "127.0.0.1");
        final UUID uuid = offlineUuid(name);
        writeLong(data, uuid.getMostSignificantBits());
        writeLong(data, uuid.getLeastSignificantBits());
        writeString(data, name);
        writeVarInt(data, 0); // no profile properties (no skin)
        final byte[] body = data.toByteArray();

        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.secret, "HmacSHA256"));
            final byte[] signature = mac.doFinal(body);
            final byte[] out = new byte[signature.length + body.length];
            System.arraycopy(signature, 0, out, 0, signature.length);
            System.arraycopy(body, 0, out, signature.length, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every JVM ships HmacSHA256", e);
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) out.write((int) (v >>> (i * 8)));
    }
}

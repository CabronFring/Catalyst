import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BotProbe {

    static final class Bot {
        final String name;
        volatile Session session;
        volatile double x, y, z;
        volatile boolean placed;
        final AtomicInteger chunks = new AtomicInteger();
        volatile String disconnectReason;
        Bot(String name) { this.name = name; }
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int count = Integer.parseInt(args[2]);
        int seconds = Integer.parseInt(args[3]);

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        Bot[] bots = new Bot[count];

        for (int i = 0; i < count; i++) {
            Bot bot = bots[i] = new Bot("cat_bot_" + (i + 1));
            MinecraftProtocol protocol = new MinecraftProtocol(bot.name);
            ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                    .setAddress(host, port).setProtocol(protocol).create();
            bot.session = session;
            session.addListener(new SessionAdapter() {
                @Override
                public void packetReceived(Session s, Packet packet) {
                    if (packet instanceof ClientboundPlayerPositionPacket pos) {
                        bot.x = pos.getPosition().getX();
                        bot.y = pos.getPosition().getY();
                        bot.z = pos.getPosition().getZ();
                        s.send(new ServerboundAcceptTeleportationPacket(pos.getId()));
                        s.send(new ServerboundMovePlayerPosPacket(false, false, bot.x, bot.y, bot.z));
                        if (!bot.placed) {
                            bot.placed = true;
                            s.send(ServerboundPlayerLoadedPacket.INSTANCE);
                        }
                    } else if (packet instanceof ClientboundLevelChunkWithLightPacket) {
                        bot.chunks.incrementAndGet();
                    } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                        // Without this acknowledgement the server stops streaming chunks.
                        s.send(new ServerboundChunkBatchReceivedPacket(64f));
                    } else if (packet instanceof ClientboundLoginPacket) {
                        System.out.println(bot.name + " logged in");
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    bot.disconnectReason = String.valueOf(event.getReason());
                    System.out.println(bot.name + " disconnected: " + event.getReason());
                }
            });
            session.connect(false);
            Thread.sleep(150);
        }

        // Walk each bot steadily in its own direction so the server has to load new terrain.
        long[] tick = {0};
        ticker.scheduleAtFixedRate(() -> {
            tick[0]++;
            for (int i = 0; i < count; i++) {
                Bot bot = bots[i];
                if (!bot.placed || bot.session == null || !bot.session.isConnected()) continue;
                double angle = (2 * Math.PI * i) / count;
                bot.x += Math.cos(angle) * 0.2;
                bot.z += Math.sin(angle) * 0.2;
                bot.session.send(new ServerboundMovePlayerPosPacket(false, false, bot.x, bot.y, bot.z));
            }
        }, 1, 50, TimeUnit.MILLISECONDS);

        Thread.sleep(seconds * 1000L);

        int connected = 0;
        for (Bot bot : bots) {
            boolean up = bot.session != null && bot.session.isConnected();
            if (up) connected++;
            System.out.printf("%s connected=%s chunks=%d pos=(%.1f, %.1f, %.1f)%s%n", bot.name, up,
                    bot.chunks.get(), bot.x, bot.y, bot.z,
                    bot.disconnectReason == null ? "" : " reason=" + bot.disconnectReason);
            if (bot.session != null) bot.session.disconnect("probe finished");
        }
        System.out.println("CONNECTED " + connected + "/" + count);
        ticker.shutdownNow();
        Thread.sleep(500);
        System.exit(0);
    }
}

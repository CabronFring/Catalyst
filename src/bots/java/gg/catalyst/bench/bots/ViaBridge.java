// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.channel.Channel;
import net.raphimc.vialoader.ViaLoader;
import net.raphimc.vialoader.impl.platform.ViaBackwardsPlatformImpl;
import net.raphimc.vialoader.impl.platform.ViaVersionPlatformImpl;
import net.raphimc.vialoader.impl.viaversion.VLCommandHandler;
import net.raphimc.vialoader.impl.viaversion.VLInjector;
import net.raphimc.vialoader.impl.viaversion.VLLoader;
import net.raphimc.vialoader.netty.VLPipeline;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;

import java.io.File;

/**
 * Lets bots that only speak MCProtocolLib's protocol join a server on another version, the
 * way ViaFabricPlus does for a real client: the bot keeps speaking its own version, and
 * ViaVersion (server older) or ViaBackwards (server newer) translate on the bot's side of
 * the connection, between the frame and packet codecs.
 *
 * The server's version is supplied by Catalyst from the running server, never by a user.
 */
final class ViaBridge {
    private final ProtocolVersion target;

    private ViaBridge(ProtocolVersion target) {
        this.target = target;
    }

    static String nativeVersion() {
        return MinecraftCodec.CODEC.getMinecraftVersion();
    }

    /** Null when no translation is needed. Throws if Via cannot reach that version. */
    static ViaBridge forServer(String serverVersion, File dataFolder) {
        if (serverVersion.equals(nativeVersion())) return null;

        final ProtocolVersion target = ProtocolVersion.getClosest(serverVersion);

        if (target == null)
            throw new IllegalArgumentException("Via cannot translate to server version " + serverVersion);

        ViaLoader.init(new ViaVersionPlatformImpl(dataFolder), new VLLoader(), new VLInjector(),
                new VLCommandHandler(), ViaBackwardsPlatformImpl::new);

        // On the client side Via cannot learn either version from the handshake: it carries
        // the bot's own version, and the default provider simply echoes that back as the
        // server's, so no translation is ever set up. Both are known here, so say so.
        final ProtocolVersion bot = ProtocolVersion.getProtocol(MinecraftCodec.CODEC.getProtocolVersion());
        Via.getManager().getProviders().use(VersionProvider.class, new VersionProvider() {
            @Override public ProtocolVersion getClientProtocol(UserConnection user) { return bot; }
            @Override public ProtocolVersion getServerProtocol(UserConnection user) { return target; }
            @Override public ProtocolVersion getClosestServerProtocol(UserConnection user) { return target; }
        });

        return new ViaBridge(target);
    }

    String target() {
        return this.target.getName();
    }

    void inject(Channel channel) {
        // true: this is the client end of the connection, so Via translates outgoing
        // packets down to the server and incoming ones back up to the bot.
        final UserConnection user = new UserConnectionImpl(channel, true);
        new ProtocolPipelineImpl(user);
        channel.pipeline().addLast(new VLPipeline(user, this.target) {
            @Override protected String compressionCodecName() { return "compression"; }
            @Override protected String packetCodecName() { return "codec"; }
            @Override protected String lengthCodecName() { return "sizer"; }
        });
    }
}

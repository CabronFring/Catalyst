// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import gg.catalyst.netty.NettyInspector;
import gg.catalyst.runtime.RuntimeModule;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.atomic.LongAdder;

/**
 * Injects a {@link CrashPacketGuard} before the Netty decoder on join and removes it on quit,
 * blocking malformed bundle-select and container-click packets before they reach the server.
 */
public final class PacketAntiCrashModule implements RuntimeModule {

    static final String HANDLER_NAME = "catalyst-crash-guard";

    private final LongAdder dropped = new LongAdder();
    private final PacketIdTable.PacketIds ids;

    private volatile boolean enabled;

    public PacketAntiCrashModule() {
        this.ids = PacketIdTable.forVersion(PacketIdTable.extractMcVersion(Bukkit.getBukkitVersion()));
    }

    @Override public String id()         { return "packet-anti-crash"; }
    @Override public String name()       { return "Packet Anti-Crash"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Injects into the Netty pipeline before the decoder to drop malformed "
             + "bundle-select and container-click packets that crash unpatched servers.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled = cfg.getBoolean("protection.packet-anti-crash.enabled", true);
    }

    @Override
    public String status() {
        if (ids == PacketIdTable.UNKNOWN) return "unsupported MC version, " + dropped.sum() + " dropped";
        final boolean hasBundle = ids.bundleItemSelected() != PacketIdTable.PacketIds.UNSUPPORTED;
        return (hasBundle ? "bundle-select + container-click" : "container-click")
             + ", " + dropped.sum() + " packet(s) dropped";
    }

    void increment() { dropped.increment(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!this.enabled || ids == PacketIdTable.UNKNOWN) return;
        try {
            final Object found = NettyInspector.channelFor(event.getPlayer());
            if (!(found instanceof Channel ch)) return;
            ch.eventLoop().execute(() -> {
                try {
                    if (ch.pipeline().get(HANDLER_NAME) == null)
                        ch.pipeline().addBefore("decoder", HANDLER_NAME, new CrashPacketGuard(this, ids));
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            final Object found = NettyInspector.channelFor(event.getPlayer());
            if (!(found instanceof Channel ch)) return;
            ch.eventLoop().execute(() -> {
                try {
                    if (ch.pipeline().get(HANDLER_NAME) != null)
                        ch.pipeline().remove(HANDLER_NAME);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}

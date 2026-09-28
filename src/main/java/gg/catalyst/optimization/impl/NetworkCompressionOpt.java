// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.platform.ServerPlatform;

/**
 * Behind a proxy, backend compression is wasted CPU: the hop is usually local and the proxy
 * compresses again for the client. Only fires behind a proxy - on a public server it would
 * send every player uncompressed traffic.
 */
public final class NetworkCompressionOpt implements Optimization {
    private static final String KEY = "network-compression-threshold";

    @Override public String id() { return "server.network-compression"; }
    @Override public String configFile() { return "server.properties"; }
    @Override public String configKey() { return KEY; }
    @Override public String name() { return "Network Compression"; }
    @Override public String category() { return "network"; }
    @Override public OptimizationLevel level() { return OptimizationLevel.MODERATE; }
    @Override public boolean appliesTo(ServerPlatform p) { return true; }

    @Override
    public String description() {
        return "network-compression-threshold sets the packet size above which the server "
             + "compresses. Behind a proxy the compression is wasted work.";
    }

    @Override
    public CheckResult check(OptimizationContext ctx) {
        if (!behindProxy(ctx)) return null;

        final String raw = ctx.serverProperties().getProperty(KEY);

        if (raw == null) return null;

        int current;

        try {
            current = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }

        if (current < 0) return null;

        return CheckResult.of(current, -1,
                "This server is behind a proxy, so its packets go to the proxy rather than "
              + "straight to players. Compressing them costs CPU for no bandwidth saving, and "
              + "the proxy still compresses properly on the player-facing side. "
              + "Only keep this if your proxy sits on a different host across a metered link.");
    }

    @Override
    public void apply(OptimizationContext ctx) {
        ctx.serverProperties().setProperty(KEY, "-1");
        ctx.markServerPropertiesDirty();
    }

    /** True when BungeeCord or Velocity forwarding is switched on. */
    private static boolean behindProxy(OptimizationContext ctx) {
        final boolean bungee = ctx.spigotYml().getBoolean("settings.bungeecord", false);
        final boolean velocity = ctx.hasPaperGlobalModern()
                && ctx.paperGlobalYml().getBoolean("proxies.velocity.enabled", false);

        return bungee || velocity;
    }
}

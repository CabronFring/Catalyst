// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;

/**
 * Checks every login on Paper, through Paper's own login event: a verified bot is recorded,
 * and let past a whitelist, ban or full server. Its own class so servers without Paper's API
 * never load it; elsewhere BukkitBotLoginListener does the same with PlayerLoginEvent.
 */
final class PaperBotLoginListener implements Listener {
    private final BotLoginCheck check;

    PaperBotLoginListener(BotLoginCheck check) {
        this.check = check;
    }

    // HIGHEST, not MONITOR: this changes the outcome, and runs after the plugins that deny.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidate(PlayerConnectionValidateLoginEvent event) {
        final PlayerConnection connection = event.getConnection();
        final String name = connection instanceof PlayerLoginConnection login && login.getAuthenticatedProfile() != null
                ? login.getAuthenticatedProfile().getName()
                : connection instanceof PlayerConfigurationConnection config ? config.getProfile().getName()
                : null;
        final InetSocketAddress client = connection.getClientAddress();
        final InetSocketAddress virtualHost = connection.getVirtualHost();

        if (name == null || client == null || virtualHost == null) return;

        if (this.check.verify(name, client.getAddress(), virtualHost.getHostString()) && !event.isAllowed())
            event.allow();
    }
}

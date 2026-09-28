// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Spawns bots straight into the bench world on Paper, through Paper's own spawn event.
 * Its own class so servers without Paper's API never load it.
 */
final class PaperBotSpawnListener implements Listener {
    private final Predicate<String> isBot;
    private final Function<String, Location> spawn;

    PaperBotSpawnListener(Predicate<String> isBot, Function<String, Location> spawn) {
        this.isBot = isBot;
        this.spawn = spawn;
    }

    @EventHandler
    public void onSpawn(AsyncPlayerSpawnLocationEvent event) {
        final String name = event.getConnection().getProfile().getName();

        if (this.isBot.test(name)) event.setSpawnLocation(this.spawn.apply(name));
    }
}

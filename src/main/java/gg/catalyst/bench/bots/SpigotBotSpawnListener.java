// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Spawns bots straight into the bench world on Spigot. Paper uses PaperBotSpawnListener,
 * since there listening to this event makes the server create the player early.
 *
 * Its own class because the event is Spigot's: BotStage checks it exists before loading
 * this, and plain CraftBukkit uses the join-time teleport instead.
 */
@SuppressWarnings("deprecation") // deprecated by Paper only; Spigot has nothing else
final class SpigotBotSpawnListener implements Listener {
    private final Predicate<String> isBot;
    private final Function<String, Location> spawn;

    SpigotBotSpawnListener(Predicate<String> isBot, Function<String, Location> spawn) {
        this.isBot = isBot;
        this.spawn = spawn;
    }

    @EventHandler
    public void onSpawn(PlayerSpawnLocationEvent event) {
        final String name = event.getPlayer().getName();

        if (this.isBot.test(name)) event.setSpawnLocation(this.spawn.apply(name));
    }
}

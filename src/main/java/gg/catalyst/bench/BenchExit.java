// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets people out of the bench worlds before deletion: back to where they entered from, else
 * their bed/anchor, else the overworld spawn (by key, not world-list order). Also on logout
 * inside, or they'd log back in at the same coords in the overworld, likely underground.
 */
final class BenchExit implements Listener {
    private final Plugin plugin;
    private final World bench;
    private final Map<UUID, Location> origins = new ConcurrentHashMap<>();
    private Runnable whenEmpty;

    BenchExit(Plugin plugin, World bench) {
        this.plugin = plugin;
        this.bench = bench;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    void unregister() {
        HandlerList.unregisterAll(this);
        this.origins.clear();
        this.whenEmpty = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        final Location to = event.getTo();

        if (to == null || !this.bench.equals(to.getWorld()) || this.bench.equals(event.getFrom().getWorld())) return;

        // Only the first entry counts: that is the place they came from outside.
        this.origins.putIfAbsent(event.getPlayer().getUniqueId(), event.getFrom().clone());
    }

    /** Moves everyone it can back out; returns the players still inside afterwards. */
    List<Player> evacuate() {
        final List<Player> stuck = new ArrayList<>();

        for (final Player p : this.bench.getPlayers()) {
            final Location back = this.returnPoint(p);

            // A plugin can cancel the teleport (combat tags, jails); then they are still here.
            if (back == null || !p.teleport(back)) stuck.add(p);
        }

        return stuck;
    }

    private Location returnPoint(Player p) {
        final Location origin = this.origins.get(p.getUniqueId());

        if (this.usable(origin)) return origin;
        final Location respawn = respawnPoint(p);

        if (this.usable(respawn)) return respawn;
        final Location overworld = overworldSpawn();

        return this.usable(overworld) ? overworld : null;
    }

    /** Where the game respawns a player with no bed or anchor: the overworld's spawn. */
    private static Location overworldSpawn() {
        try {
            final World overworld = Bukkit.getWorld(NamespacedKey.minecraft("overworld"));
            return overworld == null ? null : overworld.getSpawnLocation();
        } catch (NoSuchMethodError olderApi) {
            return null; // No world lookup by key before 1.16.5.
        }
    }

    /** Somewhere in a world that is still loaded and is not one of the bench worlds. */
    private boolean usable(Location l) {
        return l != null && l.getWorld() != null && !Benchmark.isBenchWorld(l.getWorld().getName())
                && Bukkit.getWorld(l.getWorld().getUID()) != null;
    }

    @SuppressWarnings("deprecation") // getBedSpawnLocation: the only form older servers have
    private static Location respawnPoint(Player p) {
        try {
            return p.getRespawnLocation();
        } catch (NoSuchMethodError older) {
            return p.getBedSpawnLocation();
        }
    }

    /** Runs {@code then} on the server thread once nobody is left in the bench world. */
    void whenEmpty(Runnable then) {
        this.whenEmpty = then;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeaveWorld(PlayerChangedWorldEvent event) {
        if (this.bench.equals(event.getFrom())) this.checkEmpty();
    }

    /**
     * Someone logging out inside is moved out first, while the server still has them: their
     * position is saved after this event, and saved here it would outlive the world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();

        if (!this.bench.equals(player.getWorld()))
            return;

        final Location back = this.returnPoint(player);

        if (back != null)
            player.teleport(back);

        this.checkEmpty();
    }

    private void checkEmpty() {
        if (this.whenEmpty == null) return;

        // A tick later: the leaving player still counts as in the world during the event.
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            final Runnable then = this.whenEmpty;

            if (then != null && this.bench.getPlayers().isEmpty()) {
                this.whenEmpty = null;
                then.run();
            }
        }, 1L);
    }
}

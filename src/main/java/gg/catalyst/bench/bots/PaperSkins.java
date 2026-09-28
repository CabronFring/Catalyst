// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Puts a skin on a bot's profile, and checks one took. Its own class because the profile
 * types are Paper's: Bukkit inspects every method of a listener class when registering it, so
 * one mention of them inside BotStage would stop BotStage registering at all on Spigot.
 * BotStage only calls this once it knows it is on Paper.
 */
final class PaperSkins {
    private PaperSkins() {}

    static boolean supported() {
        try {
            Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            return true;
        } catch (ClassNotFoundException notPaper) {
            return false;
        }
    }

    static void apply(AsyncPlayerPreLoginEvent event, MojangNames.Skin skin) {
        final PlayerProfile profile = event.getPlayerProfile();
        profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        event.setPlayerProfile(profile);
    }

    static boolean wearsSkin(Player player) {
        for (final ProfileProperty p : player.getPlayerProfile().getProperties())
            if (p.getName().equals("textures")) return true;

        return false;
    }
}

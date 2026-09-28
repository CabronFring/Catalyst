// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.util.List;

/** Hover text describing an installed plugin, taken from its plugin.yml. */
public final class PluginInfo {
    private static final int WRAP = 40;

    private PluginInfo() {}

    /** Null when no installed plugin has this name, so callers can show plain text instead. */
    // getDescription() is deprecated on Paper in favour of getPluginMeta(), which Spigot lacks.
    @SuppressWarnings("deprecation")
    public static String hover(String name) {
        if (name == null) return null;
        final Plugin plugin = find(name);

        if (plugin == null) return null;

        final PluginDescriptionFile d = plugin.getDescription();
        final StringBuilder tip = new StringBuilder("§b").append(d.getName()).append(" §7v").append(d.getVersion());

        final List<String> authors = d.getAuthors();

        if (!authors.isEmpty()) tip.append("\n§7by §f").append(String.join(", ", authors));

        final String description = d.getDescription();

        if (description != null && !description.isBlank()) tip.append("\n").append(wrap(description.trim()));

        final String website = d.getWebsite();

        if (website != null && !website.isBlank()) tip.append("\n§3").append(website);

        if (!plugin.isEnabled()) tip.append("\n§cDisabled");

        return tip.toString();
    }

    private static Plugin find(String name) {
        final Plugin exact = Bukkit.getPluginManager().getPlugin(name);

        if (exact != null) return exact;

        for (final Plugin p : Bukkit.getPluginManager().getPlugins())
            if (p.getName().equalsIgnoreCase(name)) return p;

        return null;
    }

    private static String wrap(String text) {
        final StringBuilder out = new StringBuilder("§7");
        int lineLength = 0;

        for (final String word : text.split("\\s+")) {
            if (lineLength > 0 && lineLength + 1 + word.length() > WRAP) {
                out.append("\n§7");
                lineLength = 0;
            } else if (lineLength > 0) {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }

        return out.toString();
    }
}

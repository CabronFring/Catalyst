// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.chat;

import org.bukkit.command.CommandSender;

/**
 * Chat lines with a clickable part. BungeeCord components rather than Adventure, which is
 * Paper-only; plain CraftBukkit has neither and gets text saying what to type.
 */
public interface ChatLinks {
    /**
     * RUN fires immediately, so it is reserved for read-only actions. Anything that
     * changes state uses SUGGEST, which fills the chat bar and waits for Enter.
     */
    enum Click { RUN, SUGGEST, COPY, OPEN_URL }

    /**
     * @param before plain text before the clickable part
     * @param label  the clickable text itself
     * @param after  plain text after it
     * @param value  command or text the click applies
     * @param hover  tooltip, or null
     */
    void send(CommandSender to, String before, String label, String after,
              String value, String hover, Click click);

    /** Like send, but the middle part only shows a tooltip and does nothing on click. A null hover sends plain text. */
    void hover(CommandSender to, String before, String label, String after, String hover);

    boolean isClickable();

    static ChatLinks create() {
        if (classExists("net.md_5.bungee.api.chat.TextComponent")
         && hasSpigotSender()) return new SpigotChatLinks();

        return new PlainChatLinks();
    }

    private static boolean hasSpigotSender() {
        try {
            CommandSender.class.getMethod("spigot");
            return true;
        } catch (NoSuchMethodException | RuntimeException e) {
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}

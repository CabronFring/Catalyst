// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.chat;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Fallback for servers with no component chat. The clickable value is still shown as
 * text, so the information is never lost - only the convenience.
 */
final class PlainChatLinks implements ChatLinks {
    @Override
    public void send(CommandSender to, String before, String label, String after,
                     String value, String hover, Click click) {
        // Where the label is not the command itself (a "[teleport]" button), show the command
        // after it, so it can still be typed.
        final String plainLabel = label.replaceAll("§.", "");
        final boolean shown = value == null || plainLabel.contains(value) || plainLabel.contains(value.replaceFirst("^/", ""));
        to.sendMessage(before + label + after + (shown ? "" : " " + ChatColor.DARK_GRAY + "(" + value + ")"));
    }

    @Override
    public void hover(CommandSender to, String before, String label, String after, String hover) {
        to.sendMessage(before + label + after);
    }

    @Override
    public boolean isClickable() {
        return false;
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.chat;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Spigot and every Paper fork. Never loaded on plain CraftBukkit, which has neither
 * the component classes nor CommandSender#spigot.
 */
final class SpigotChatLinks implements ChatLinks {
    @Override
    public void send(CommandSender to, String before, String label, String after,
                     String value, String hover, Click click) {

        final TextComponent line = new TextComponent(TextComponent.fromLegacyText(before));

        final TextComponent clickable = new TextComponent(TextComponent.fromLegacyText(label));
        clickable.setClickEvent(new ClickEvent(actionFor(click), value));

        // Every link says exactly what a click will do, so nothing runs that the reader has not
        // seen: the tooltip always ends with the command or address itself.
        final String tip = (hover == null || hover.isBlank() ? "" : "§f" + hover + "\n") + whatItDoes(click) + value;
        clickable.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(tip))));

        line.addExtra(clickable);

        if (after != null && !after.isEmpty())
            line.addExtra(new TextComponent(TextComponent.fromLegacyText(after)));

        try {
            to.spigot().sendMessage(line);
        } catch (RuntimeException malformed) {
            // A bad value - an unexpected URL, say - can throw when the client component is built.
            // One broken link must not abort the rest of the report, so fall back to plain text.
            to.sendMessage(before + label + (after == null ? "" : after));
        }
    }

    @Override
    public void hover(CommandSender to, String before, String label, String after, String hover) {
        if (hover == null) {
            to.sendMessage(before + label + after);
            return;
        }
        final TextComponent line = new TextComponent(TextComponent.fromLegacyText(before));
        final TextComponent hovered = new TextComponent(TextComponent.fromLegacyText(label));
        hovered.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover))));
        line.addExtra(hovered);

        if (after != null && !after.isEmpty())
            line.addExtra(new TextComponent(TextComponent.fromLegacyText(after)));
        to.spigot().sendMessage(line);
    }

    private static String whatItDoes(Click click) {
        return switch (click) {
            case RUN -> ChatColor.GRAY + "Click runs: " + ChatColor.AQUA;
            case SUGGEST -> ChatColor.GRAY + "Click puts this in your chat bar (nothing runs until you press Enter): " + ChatColor.AQUA;
            case COPY -> ChatColor.GRAY + "Click copies: " + ChatColor.AQUA;
            case OPEN_URL -> ChatColor.GRAY + "Click opens: " + ChatColor.AQUA;
        };
    }

    private static ClickEvent.Action actionFor(Click click) {
        return switch (click) {
            case RUN -> ClickEvent.Action.RUN_COMMAND;
            case SUGGEST -> ClickEvent.Action.SUGGEST_COMMAND;
            case COPY -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            case OPEN_URL -> ClickEvent.Action.OPEN_URL;
        };
    }

    @Override
    public boolean isClickable() {
        return true;
    }
}

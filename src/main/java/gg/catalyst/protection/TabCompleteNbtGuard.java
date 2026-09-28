// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import gg.catalyst.runtime.RuntimeModule;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.concurrent.atomic.LongAdder;

/**
 * Cancels tab-complete requests whose buffer contains an abnormally deep NBT selector before
 * the command dispatcher parses it. The entity selector parser is recursive and overflows the
 * stack on crafted inputs with more than ~25 nested braces.
 */
public final class TabCompleteNbtGuard implements RuntimeModule {

    private final LongAdder blocked = new LongAdder();

    private volatile boolean enabled;
    private volatile int maxBraceDepth;
    private volatile int maxNbtBracketDepth;

    @Override public String id()         { return "tab-complete-nbt"; }
    @Override public String name()       { return "Tab-Complete NBT Guard"; }
    @Override public boolean isEnabled() { return this.enabled; }

    @Override
    public String description() {
        return "Cancels tab-complete requests with deep NBT brace nesting that would "
             + "stack-overflow the command dispatcher's entity selector parser.";
    }

    @Override
    public void configure(FileConfiguration cfg) {
        this.enabled            = cfg.getBoolean("protection.tab-complete-nbt.enabled", true);
        this.maxBraceDepth      = Math.max(1, cfg.getInt("protection.tab-complete-nbt.max-brace-depth", 25));
        this.maxNbtBracketDepth = Math.max(1, cfg.getInt("protection.tab-complete-nbt.max-nbt-bracket-depth", 15));
    }

    @Override
    public String status() {
        return "brace≤" + maxBraceDepth + " nbt-bracket≤" + maxNbtBracketDepth
             + ", " + blocked.sum() + " request(s) blocked";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!this.enabled) return;

        final String buf = event.getBuffer();

        if (buf == null || buf.isEmpty()) return;

        if (countChar(buf, '{') > this.maxBraceDepth) {
            event.setCancelled(true);
            blocked.increment();
            return;
        }

        if (buf.contains("nbt") && countChar(buf, '[') > this.maxNbtBracketDepth) {
            event.setCancelled(true);
            blocked.increment();
        }
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == c) count++;
        return count;
    }
}

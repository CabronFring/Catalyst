// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import gg.catalyst.runtime.RuntimeModule;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class ProtectionManager {
    private final Plugin plugin;
    private final List<RuntimeModule> guards = List.of(
            new PacketAntiCrashModule(),
            new TabCompleteNbtGuard(),
            new BookExploitGuard(),
            new SignOverflowGuard(),
            new AnvilRenameGuard(),
            new CreativeNbtGuard()
    );

    private boolean registered = false;

    public ProtectionManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public List<RuntimeModule> guards() { return this.guards; }

    public void start() {
        this.reload();

        if (this.registered) return;

        for (final RuntimeModule guard : this.guards)
            this.plugin.getServer().getPluginManager().registerEvents(guard, this.plugin);
        this.registered = true;
    }

    public void reload() {
        for (final RuntimeModule guard : this.guards)
            guard.configure(this.plugin.getConfig());
    }

    public long enabledCount() {
        return this.guards.stream().filter(RuntimeModule::isEnabled).count();
    }
}

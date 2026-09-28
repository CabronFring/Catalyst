// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.runtime;

import org.bukkit.plugin.Plugin;

import java.util.List;

public final class RuntimeManager {
    private final Plugin plugin;
    private final List<RuntimeModule> modules = List.of(
            new MobLimiterModule(),
            new ItemMergeModule()
    );

    private boolean registered = false;

    public RuntimeManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public List<RuntimeModule> modules() { return this.modules; }

    /**
     * Listeners stay registered for the plugin's lifetime; each module checks its own
     * enabled flag, so a reload can turn one off without re-registering anything.
     */
    public void start() {
        this.reload();

        if (this.registered) return;

        for (final RuntimeModule module : this.modules)
            this.plugin.getServer().getPluginManager().registerEvents(module, this.plugin);
        this.registered = true;
    }

    public void reload() {
        for (final RuntimeModule module : this.modules)
            module.configure(this.plugin.getConfig());
    }

    public long enabledCount() {
        return this.modules.stream().filter(RuntimeModule::isEnabled).count();
    }
}

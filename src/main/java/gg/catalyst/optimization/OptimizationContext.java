// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

import gg.catalyst.platform.ServerPlatform;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

public final class OptimizationContext {
    private final ServerPlatform platform;
    private final File serverRoot;
    private final Logger log;

    private Properties serverProperties;
    private YamlConfiguration bukkitYml;
    private YamlConfiguration spigotYml;
    private YamlConfiguration paperGlobalYml;
    private YamlConfiguration paperWorldDefaultsYml;
    private YamlConfiguration purpurYml;
    private YamlConfiguration pufferfishYml;
    private YamlConfiguration leafGlobalYml;
    private YamlConfiguration canvasServerYml;
    private YamlConfiguration canvasWorldsYml;

    // Track which files were loaded (and thus may have been modified)
    private boolean serverPropertiesDirty = false;
    private boolean bukkitYmlDirty = false;
    private boolean spigotYmlDirty = false;
    private boolean paperGlobalDirty = false;
    private boolean paperWorldDirty = false;
    private boolean purpurYmlDirty = false;
    private boolean pufferfishYmlDirty = false;
    private boolean leafGlobalDirty = false;
    private boolean canvasServerDirty = false;
    private boolean canvasWorldsDirty = false;

    public OptimizationContext(ServerPlatform platform, File serverRoot, Logger log) {
        this.platform = platform;
        this.serverRoot = serverRoot;
        this.log = log;
    }

    public ServerPlatform platform() { return this.platform; }
    public File serverRoot() { return this.serverRoot; }
    public Logger log() { return this.log; }

    // ── server.properties ──────────────────────────────────────────────────

    public Properties serverProperties() {
        if (this.serverProperties == null) {
            this.serverProperties = new Properties();
            final File f = new File(this.serverRoot, "server.properties");

            if (f.exists()) {
                try (final var reader = new FileReader(f)) {
                    this.serverProperties.load(reader);
                } catch (IOException e) {
                    this.log.warning("Could not read server.properties: " + e.getMessage());
                }
            }
        }

        return this.serverProperties;
    }

    public void markServerPropertiesDirty() { this.serverPropertiesDirty = true; }

    // ── bukkit.yml ─────────────────────────────────────────────────────────

    public YamlConfiguration bukkitYml() {
        if (this.bukkitYml == null)
            this.bukkitYml = this.loadYaml("bukkit.yml");

        return this.bukkitYml;
    }

    public void markBukkitYmlDirty() { this.bukkitYmlDirty = true; }

    // ── spigot.yml ─────────────────────────────────────────────────────────

    public YamlConfiguration spigotYml() {
        if (this.spigotYml == null)
            this.spigotYml = this.loadYaml("spigot.yml");

        return this.spigotYml;
    }

    public void markSpigotYmlDirty() { this.spigotYmlDirty = true; }

    // ── paper-global.yml ───────────────────────────────────────────────────

    public YamlConfiguration paperGlobalYml() {
        if (this.paperGlobalYml == null)
            this.paperGlobalYml = this.loadYaml(this.hasPaperGlobalModern() ? "config/paper-global.yml" : "paper.yml");

        return this.paperGlobalYml;
    }

    public boolean hasPaperGlobalModern() {
        return new File(this.serverRoot, "config/paper-global.yml").exists();
    }

    public void markPaperGlobalDirty() { this.paperGlobalDirty = true; }

    // ── paper-world-defaults.yml ───────────────────────────────────────────

    public YamlConfiguration paperWorldDefaultsYml() {
        if (this.paperWorldDefaultsYml == null)
            this.paperWorldDefaultsYml = this.loadYaml("config/paper-world-defaults.yml");

        return this.paperWorldDefaultsYml;
    }

    public boolean hasPaperWorldDefaults() {
        return new File(this.serverRoot, "config/paper-world-defaults.yml").exists();
    }

    public void markPaperWorldDirty() { this.paperWorldDirty = true; }

    // ── purpur.yml ─────────────────────────────────────────────────────────

    public YamlConfiguration purpurYml() {
        if (this.purpurYml == null)
            this.purpurYml = this.loadYaml("purpur.yml");

        return this.purpurYml;
    }

    public boolean hasPurpurYml() {
        return new File(this.serverRoot, "purpur.yml").exists();
    }

    public void markPurpurYmlDirty() { this.purpurYmlDirty = true; }

    // ── pufferfish.yml ─────────────────────────────────────────────────────

    public YamlConfiguration pufferfishYml() {
        if (this.pufferfishYml == null)
            this.pufferfishYml = this.loadYaml("pufferfish.yml");

        return this.pufferfishYml;
    }

    public boolean hasPufferfishYml() {
        return new File(this.serverRoot, "pufferfish.yml").exists();
    }

    public void markPufferfishYmlDirty() { this.pufferfishYmlDirty = true; }

    // ── leaf-global.yml ────────────────────────────────────────────────────

    public YamlConfiguration leafGlobalYml() {
        if (this.leafGlobalYml == null)
            this.leafGlobalYml = this.loadYaml("config/leaf-global.yml");

        return this.leafGlobalYml;
    }

    public boolean hasLeafGlobal() {
        return new File(this.serverRoot, "config/leaf-global.yml").exists();
    }

    public void markLeafGlobalDirty() { this.leafGlobalDirty = true; }

    // ── canvas-server.yml / canvas-worlds.yml ──────────────────────────────

    public YamlConfiguration canvasServerYml() {
        if (this.canvasServerYml == null)
            this.canvasServerYml = this.loadYaml("config/canvas-server.yml");

        return this.canvasServerYml;
    }

    public boolean hasCanvasServer() {
        return new File(this.serverRoot, "config/canvas-server.yml").exists();
    }

    public void markCanvasServerDirty() { this.canvasServerDirty = true; }

    /** The defaults every world starts from; a world's own canvas-patch.yml can still override them. */
    public YamlConfiguration canvasWorldsYml() {
        if (this.canvasWorldsYml == null)
            this.canvasWorldsYml = this.loadYaml("config/canvas-worlds.yml");

        return this.canvasWorldsYml;
    }

    public boolean hasCanvasWorlds() {
        return new File(this.serverRoot, "config/canvas-worlds.yml").exists();
    }

    public void markCanvasWorldsDirty() { this.canvasWorldsDirty = true; }

    // ── save ───────────────────────────────────────────────────────────────

    public void saveAll() throws IOException {
        if (this.serverPropertiesDirty && this.serverProperties != null) {
            final File f = new File(this.serverRoot, "server.properties");

            try (final var writer = new FileWriter(f)) {
                this.serverProperties.store(writer, "Minecraft server properties");
            }
        }

        if (this.bukkitYmlDirty && this.bukkitYml != null)
            this.bukkitYml.save(new File(this.serverRoot, "bukkit.yml"));

        if (this.spigotYmlDirty && this.spigotYml != null)
            this.spigotYml.save(new File(this.serverRoot, "spigot.yml"));

        if (this.paperGlobalDirty && this.paperGlobalYml != null) {
            final File f = this.hasPaperGlobalModern()
                    ? new File(this.serverRoot, "config/paper-global.yml")
                    : new File(this.serverRoot, "paper.yml");
            this.paperGlobalYml.save(f);
        }

        if (this.paperWorldDirty && this.paperWorldDefaultsYml != null)
            this.paperWorldDefaultsYml.save(new File(this.serverRoot, "config/paper-world-defaults.yml"));

        if (this.purpurYmlDirty && this.purpurYml != null)
            this.purpurYml.save(new File(this.serverRoot, "purpur.yml"));

        if (this.pufferfishYmlDirty && this.pufferfishYml != null)
            this.pufferfishYml.save(new File(this.serverRoot, "pufferfish.yml"));

        if (this.leafGlobalDirty && this.leafGlobalYml != null)
            this.leafGlobalYml.save(new File(this.serverRoot, "config/leaf-global.yml"));

        if (this.canvasServerDirty && this.canvasServerYml != null)
            this.canvasServerYml.save(new File(this.serverRoot, "config/canvas-server.yml"));

        if (this.canvasWorldsDirty && this.canvasWorldsYml != null)
            this.canvasWorldsYml.save(new File(this.serverRoot, "config/canvas-worlds.yml"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private YamlConfiguration loadYaml(String relativePath) {
        final File f = new File(this.serverRoot, relativePath);

        if (!f.exists()) return new YamlConfiguration();

        return YamlConfiguration.loadConfiguration(f);
    }
}

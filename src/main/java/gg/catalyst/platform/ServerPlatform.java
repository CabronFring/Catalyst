// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

public enum ServerPlatform {
    CANVAS ("Canvas", true, true, false, false, false, true, true),
    FOLIA ("Folia", true, true, false, false, false, false, true),
    LEAF ("Leaf", true, true, true, false, true, false, false),
    PURPUR ("Purpur", true, true, true, false, false, false, false),
    PUFFERFISH ("Pufferfish", true, true, false, true, false, false, false),
    PAPER ("Paper", true, true, false, false, false, false, false),
    SPIGOT ("Spigot", true, false, false, false, false, false, false),
    BUKKIT ("Bukkit", false, false, false, false, false, false, false),
    UNKNOWN ("Unknown", false, false, false, false, false, false, false);

    public final String displayName;
    public final boolean hasSpigotConfig;
    public final boolean hasPaperConfig;
    public final boolean hasPurpurConfig;
    public final boolean hasPufferfishConfig;
    public final boolean hasLeafConfig;
    public final boolean hasCanvasConfig;
    public final boolean isFolia;

    ServerPlatform(String displayName, boolean hasSpigotConfig,
                   boolean hasPaperConfig, boolean hasPurpurConfig, boolean hasPufferfishConfig,
                   boolean hasLeafConfig, boolean hasCanvasConfig, boolean isFolia) {
        this.displayName = displayName;
        this.hasSpigotConfig = hasSpigotConfig;
        this.hasPaperConfig = hasPaperConfig;
        this.hasPurpurConfig = hasPurpurConfig;
        this.hasPufferfishConfig = hasPufferfishConfig;
        this.hasLeafConfig = hasLeafConfig;
        this.hasCanvasConfig = hasCanvasConfig;
        this.isFolia = isFolia;
    }

    public boolean isAtLeastSpigot() { return this.hasSpigotConfig; }
    public boolean isAtLeastPaper() { return this.hasPaperConfig; }
}

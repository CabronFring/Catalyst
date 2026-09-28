// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

public enum ServerPlatform {
    FOLIA ("Folia", true, true, false, true),
    PURPUR ("Purpur", true, true, true, false),
    PAPER ("Paper", true, true, false, false),
    SPIGOT ("Spigot", true, false, false, false),
    BUKKIT ("Bukkit", false, false, false, false),
    UNKNOWN ("Unknown", false, false, false, false);

    public final String displayName;
    public final boolean hasSpigotConfig;
    public final boolean hasPaperConfig;
    public final boolean hasPurpurConfig;
    public final boolean isFolia;

    ServerPlatform(String displayName, boolean hasSpigotConfig,
                   boolean hasPaperConfig, boolean hasPurpurConfig, boolean isFolia) {
        this.displayName = displayName;
        this.hasSpigotConfig = hasSpigotConfig;
        this.hasPaperConfig = hasPaperConfig;
        this.hasPurpurConfig = hasPurpurConfig;
        this.isFolia = isFolia;
    }

    public boolean isAtLeastSpigot() { return this.hasSpigotConfig; }
    public boolean isAtLeastPaper() { return this.hasPaperConfig; }
}

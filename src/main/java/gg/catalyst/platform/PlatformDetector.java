// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.platform;

import org.bukkit.Bukkit;

import java.io.File;

public final class PlatformDetector {
    private PlatformDetector() {}

    /**
     * Identifies the platform from classes present at runtime, not config files - a former Purpur
     * server now on Paper still has purpur.yml, but loaded classes can't lie about what's running.
     * Most specific first, since every fork below is also a Paper server.
     */
    public static ServerPlatform detect() {
        if (isFolia()) return ServerPlatform.FOLIA;

        if (isPurpur()) return ServerPlatform.PURPUR;

        if (isPaper()) return ServerPlatform.PAPER;

        if (isSpigot()) return ServerPlatform.SPIGOT;

        if (isBukkit()) return ServerPlatform.BUKKIT;

        return ServerPlatform.UNKNOWN;
    }

    /**
     * Folia has no global tick thread, so Bukkit.getScheduler() throws there.
     * RegionizedServer exists only on Folia.
     */
    public static boolean isFolia() {
        return classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    public static boolean isPurpur() {
        return classExists("org.purpurmc.purpur.PurpurConfig");
    }

    public static boolean isPaper() {
        return classExists("io.papermc.paper.configuration.GlobalConfiguration")
            || classExists("com.destroystokyo.paper.PaperConfig");
    }

    public static boolean isSpigot() {
        return classExists("org.spigotmc.SpigotConfig");
    }

    public static boolean isBukkit() {
        return classExists("org.bukkit.craftbukkit.CraftServer")
            || classExistsInVersionedPackage("org.bukkit.craftbukkit.%s.CraftServer");
    }

    /** Paper 1.19.4+ and Folia expose the async scheduler; Spigot and Bukkit do not. */
    public static boolean hasAsyncScheduler() {
        return classExists("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
    }

    /** The server root directory, where server.properties lives. */
    public static File serverRoot() {
        return new File(System.getProperty("user.dir"));
    }

    /**
     * Older CraftBukkit builds put CraftServer inside a version package such as
     * org.bukkit.craftbukkit.v1_20_R3, so the unversioned name alone would miss them.
     */
    private static boolean classExistsInVersionedPackage(String template) {
        try {
            final String serverPackage = Bukkit.getServer().getClass().getPackage().getName();
            final String version = serverPackage.substring(serverPackage.lastIndexOf('.') + 1);
            return classExists(String.format(template, version));
        } catch (Throwable t) {
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

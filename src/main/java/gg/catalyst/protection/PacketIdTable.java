// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serverbound-play packet IDs for the exploits Catalyst guards against, keyed by MC version.
 * IDs come from the packets.json report Mojang's data generator writes for each release
 * (java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports).
 */
final class PacketIdTable {

    record PacketIds(int bundleItemSelected, int containerClick) {
        static final int UNSUPPORTED = -1;
    }

    static final PacketIds UNKNOWN = new PacketIds(PacketIds.UNSUPPORTED, PacketIds.UNSUPPORTED);

    private static final Map<String, PacketIds> TABLE = new HashMap<>();

    static {
        // 1.21 / 1.21.1: bundle item selected is not in the protocol yet.
        final PacketIds v1_21 = new PacketIds(PacketIds.UNSUPPORTED, 0x0E);
        TABLE.put("1.21", v1_21);
        TABLE.put("1.21.1", v1_21);

        final PacketIds v1_21_2 = new PacketIds(0x02, 0x10);
        for (int patch = 2; patch <= 5; patch++) TABLE.put("1.21." + patch, v1_21_2);

        // 1.21.6 added a packet ahead of container_click.
        final PacketIds v1_21_6 = new PacketIds(0x02, 0x11);
        for (int patch = 6; patch <= 11; patch++) TABLE.put("1.21." + patch, v1_21_6);

        // 26.1 added attack at 0x01, which moves both.
        final PacketIds v26 = new PacketIds(0x03, 0x12);
        TABLE.put("26.1", v26);
        TABLE.put("26.1.1", v26);
        TABLE.put("26.1.2", v26);
        TABLE.put("26.2", v26);
        TABLE.put("26.3", v26);
    }

    /** Unlisted versions get UNKNOWN: a wrong ID would inspect some other packet, so guessing is worse than skipping. */
    static PacketIds forVersion(String mcVersion) {
        return TABLE.getOrDefault(mcVersion, UNKNOWN);
    }

    /**
     * The leading version number of {@code Bukkit.getBukkitVersion()}: "1.21.4-R0.1-SNAPSHOT" and,
     * since 26.1, "26.3.build.211-stable" give "1.21.4" and "26.3".
     */
    static String extractMcVersion(String bukkitVersion) {
        final Matcher m = LEADING_VERSION.matcher(bukkitVersion);
        return m.find() ? m.group() : bukkitVersion;
    }

    private static final Pattern LEADING_VERSION = Pattern.compile("^\\d+(\\.\\d+)*");

    private PacketIdTable() {}
}

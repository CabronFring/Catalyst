// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import java.util.HashMap;
import java.util.Map;

/**
 * Serverbound-play packet IDs for the exploits Catalyst guards against, keyed by MC version.
 * IDs sourced from PrismarineJS minecraft-data protocol.json per version.
 */
final class PacketIdTable {

    record PacketIds(int bundleItemSelected, int containerClick) {
        static final int UNSUPPORTED = -1;
    }

    static final PacketIds UNKNOWN = new PacketIds(PacketIds.UNSUPPORTED, PacketIds.UNSUPPORTED);

    private static final Map<String, PacketIds> TABLE = new HashMap<>();

    static {
        // 1.21 / 1.21.1 — protocol 767; bundle item selected not yet in the protocol.
        final PacketIds v767 = new PacketIds(PacketIds.UNSUPPORTED, 0x0E);
        TABLE.put("1.21",   v767);
        TABLE.put("1.21.1", v767);

        // 1.21.2+ — protocol 768+; bundle-select 0x02, container-click 0x10.
        final PacketIds v768plus = new PacketIds(0x02, 0x10);
        TABLE.put("1.21.2", v768plus);
        TABLE.put("1.21.3", v768plus);
        TABLE.put("1.21.4", v768plus);
        TABLE.put("1.21.5", v768plus);
    }

    static PacketIds forVersion(String mcVersion) {
        final PacketIds ids = TABLE.get(mcVersion);
        if (ids != null) return ids;

        // Any unlisted 1.21.x patch release (e.g. 1.21.11) uses the 1.21.2+ layout.
        if (mcVersion.startsWith("1.21.")) {
            try {
                if (Integer.parseInt(mcVersion.substring(5)) >= 2)
                    return TABLE.get("1.21.3");
            } catch (NumberFormatException ignored) {}
        }

        return UNKNOWN;
    }

    /** Strips the build suffix from {@code Bukkit.getBukkitVersion()}, e.g. {@code "1.21.4-R0.1-SNAPSHOT"} → {@code "1.21.4"}. */
    static String extractMcVersion(String bukkitVersion) {
        final int dash = bukkitVersion.indexOf('-');
        return dash == -1 ? bukkitVersion : bukkitVersion.substring(0, dash);
    }

    private PacketIdTable() {}
}

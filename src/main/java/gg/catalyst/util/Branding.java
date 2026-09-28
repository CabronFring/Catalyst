// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

/**
 * The plugin's name as plugin.yml declares it, for every message, report and path that
 * shows it. Set once at enable; the default only matters in unit tests.
 */
public final class Branding {
    private static volatile String name = "Catalyst";

    /** Gray separator line used to frame each command's output block in chat. */
    public static final String SEP = "§7====================";

    private Branding() {}

    public static void set(String pluginName) {
        if (pluginName != null && !pluginName.isBlank()) name = pluginName;
    }

    public static String name() {
        return name;
    }
}

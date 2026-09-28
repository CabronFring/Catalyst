// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.runtime;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;

/**
 * An active optimization: real behaviour that runs while the server is up, as opposed
 * to a config value that only takes effect through a file.
 */
public interface RuntimeModule extends Listener {
    String id();

    String name();

    /** One sentence on what this module does while running. */
    String description();

    /** Reads its own settings out of Catalyst's config. */
    void configure(FileConfiguration cfg);

    boolean isEnabled();

    /** Short human-readable line describing what it has done so far. */
    String status();
}

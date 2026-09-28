// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.util;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldPathsTest {
    @Test
    void minecraft26DimensionFolderLeadsBackToTheSave() {
        final File overworld = new File("server/world/dimensions/minecraft/overworld");
        assertEquals(new File("server/world"), WorldPaths.saveRoot(overworld));
    }

    @Test
    void classicWorldFolderIsTheSaveItself() {
        final File world = new File("server/world");
        assertEquals(world, WorldPaths.saveRoot(world));
    }
}

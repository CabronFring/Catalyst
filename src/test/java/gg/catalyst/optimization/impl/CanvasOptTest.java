// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization.impl;

import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationContext;
import gg.catalyst.platform.ServerPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the config files Canvas 26.3 build 991 generated on first start. */
class CanvasOptTest {
    private static final List<Optimization> OPTS = List.of(
            new CanvasChunkSystemOpt(), new CanvasVelocityPacketOpt(), new CanvasVillagerPoiOpt(),
            new CanvasProjectileOpt(), new CanvasEntityPortalsOpt());

    @TempDir Path root;

    private OptimizationContext context() {
        return new OptimizationContext(ServerPlatform.CANVAS, this.root.toFile(), Logger.getLogger("test"));
    }

    private void copyFixtures() throws Exception {
        Files.createDirectories(this.root.resolve("config"));

        for (final String name : List.of("canvas-server.yml", "canvas-worlds.yml"))
            Files.copy(new File(CanvasOptTest.class.getResource("/fixtures/canvas/" + name).toURI()).toPath(),
                    this.root.resolve("config").resolve(name));
    }

    @Test
    void everyOptFindsCanvasDefaultsAndApplies() throws Exception {
        this.copyFixtures();
        final OptimizationContext ctx = this.context();

        for (final Optimization opt : OPTS) {
            assertTrue(opt.appliesTo(ServerPlatform.CANVAS), opt.id());
            assertNotNull(opt.check(ctx), opt.id() + " should flag Canvas's default");
            opt.apply(ctx);
        }
        ctx.saveAll();

        final OptimizationContext reread = this.context();

        for (final Optimization opt : OPTS)
            assertNull(opt.check(reread), opt.id() + " should be satisfied after apply");

        assertEquals("FILTERED", reread.canvasServerYml().getString("chunk-system.fluid-post-processing-algorithm"));
        assertEquals(8, reread.canvasWorldsYml().getInt(
                "entities.projectiles.max-projectile-chunk-loads-per-projectile-before-removal"));
        assertTrue(Files.readString(this.root.resolve("config/canvas-server.yml")).contains("networking:"));
    }

    @Test
    void nothingIsWrittenWithoutCanvasFiles() throws Exception {
        final OptimizationContext ctx = this.context();

        for (final Optimization opt : OPTS) {
            assertNull(opt.check(ctx), opt.id());
            opt.apply(ctx);
        }
        ctx.saveAll();

        assertTrue(Files.notExists(this.root.resolve("config/canvas-server.yml")));
        assertTrue(Files.notExists(this.root.resolve("config/canvas-worlds.yml")));
    }

    @Test
    void otherPlatformsAreSkipped() {
        for (final Optimization opt : OPTS) {
            assertTrue(!opt.appliesTo(ServerPlatform.FOLIA), opt.id());
            assertTrue(!opt.appliesTo(ServerPlatform.PAPER), opt.id());
        }
    }
}

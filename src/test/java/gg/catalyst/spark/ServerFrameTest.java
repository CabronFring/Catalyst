// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerFrameTest {
    private static SparkProfile.Node node(String cls, String method) {
        final SparkProfile.Node n = new SparkProfile.Node();
        n.className = cls;
        n.methodName = method;

        return n;
    }

    @Test
    void namesTheDeepestServerFrameNotTheLibraryLeaf() {
        final List<SparkProfile.Node> path = List.of(
                node("net.minecraft.server.MinecraftServer", "tickServer"),
                node("net.minecraft.network.Connection", "tick"),
                node("java.util.HashMap", "get"));
        assertEquals("Connection.tick", ProfileAnalysis.serverFrame(path));
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PacketIdTableTest {

    @Test
    void idsMatchMojangReports() {
        assertEquals(new PacketIdTable.PacketIds(PacketIdTable.PacketIds.UNSUPPORTED, 0x0E), PacketIdTable.forVersion("1.21.1"));
        assertEquals(new PacketIdTable.PacketIds(0x02, 0x10), PacketIdTable.forVersion("1.21.5"));
        assertEquals(new PacketIdTable.PacketIds(0x02, 0x11), PacketIdTable.forVersion("1.21.11"));
        assertEquals(new PacketIdTable.PacketIds(0x03, 0x12), PacketIdTable.forVersion("26.3"));
    }

    @Test
    void unlistedVersionIsNotGuessed() {
        assertSame(PacketIdTable.UNKNOWN, PacketIdTable.forVersion("26.4"));
        assertSame(PacketIdTable.UNKNOWN, PacketIdTable.forVersion("1.21.12"));
    }

    @Test
    void stripsBuildSuffix() {
        assertEquals("1.21.11", PacketIdTable.extractMcVersion("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("26.3", PacketIdTable.extractMcVersion("26.3.build.211-stable"));
    }
}

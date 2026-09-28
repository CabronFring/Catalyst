// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerLimitsTest {
    @Test
    void cgroupV2NoLimit() {
        assertEquals(OptionalLong.empty(), ContainerLimits.parseLimit("max\n"));
    }

    @Test
    void cgroupV1NoLimitIsAHugeNumber() {
        assertEquals(OptionalLong.empty(), ContainerLimits.parseLimit("9223372036854771712\n"));
    }

    @Test
    void realLimitIsRead() {
        assertEquals(OptionalLong.of(4294967296L), ContainerLimits.parseLimit("4294967296\n"));
    }

    @Test
    void reclaimableCacheIsReadFromMemoryStat() {
        final String stat = "anon 1073741824\nfile 2147483648\nactive_file 536870912\ninactive_file 1610612736\n";
        assertEquals(1610612736L, ContainerLimits.parseStat(stat, "inactive_file"));

        // v1 names it total_inactive_file; "inactive_file" must not match the tail of another key.
        assertEquals(0, ContainerLimits.parseStat("total_inactive_file 5\n", "inactive_file"));
        assertEquals(0, ContainerLimits.parseStat("", "inactive_file"));
    }

    @Test
    void heapGrowsWithBotCount() {
        assertEquals(163, ContainerLimits.mib(ContainerLimits.botHeapBytes(1)));
        assertEquals(310, ContainerLimits.mib(ContainerLimits.botHeapBytes(50)));
    }
}

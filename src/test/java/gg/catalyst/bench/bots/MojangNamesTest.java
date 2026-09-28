// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MojangNamesTest {
    @Test
    void parsesUndashedIds() {
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), MojangNames.parse("069a79f444e94726a5befca90e38aaf5"));
        assertEquals(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), MojangNames.parse("ffffffffffffffffffffffffffffffff"));
    }

    @Test
    void rejectsAnythingElse() {
        assertThrows(IllegalArgumentException.class, () -> MojangNames.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertThrows(IllegalArgumentException.class, () -> MojangNames.parse(""));
    }
}

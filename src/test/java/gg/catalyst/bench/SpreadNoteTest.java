// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpreadNoteTest {
    @Test
    void runsThatAgreeSayNothing() {
        assertNull(Benchmark.spreadNote(List.of(10.0, 11.0, 10.5), 10.5));
    }

    @Test
    void aFallingRunIsCalledSettling() {
        final String note = Benchmark.spreadNote(List.of(43.741, 29.278, 24.254), 29.278);
        assertNotNull(note);
        assertTrue(note.contains("24.3-43.7") && note.contains("settling"), note);
    }

    @Test
    void jumpyRunsAreCalledUnsteady() {
        final String note = Benchmark.spreadNote(List.of(10.0, 20.0, 12.0), 12.0);
        assertNotNull(note);
        assertTrue(note.contains("not steady"), note);
    }

    @Test
    void tinyStagesAreLeftAlone() {
        assertNull(Benchmark.spreadNote(List.of(0.1, 0.5, 0.3), 0.3));
    }
}

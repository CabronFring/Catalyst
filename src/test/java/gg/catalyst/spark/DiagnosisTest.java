// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DiagnosisTest {
    private static SparkBridge.Snapshot noMspt(double tps) {
        return new SparkBridge.Snapshot(tps, tps, tps, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                0.1, 0.2, 0, 0, 0, "none");
    }

    @Test
    void healthyWithoutTickTimes() {
        final List<Diagnosis.Culprit> found = Diagnosis.analyse(noMspt(20.0));
        assertEquals(1, found.size());
        assertEquals("Nothing looks wrong", found.get(0).title());
        assertFalse(found.get(0).evidence().contains("NaN"));
    }

    @Test
    void slowWithoutTickTimes() {
        final List<Diagnosis.Culprit> found = Diagnosis.analyse(noMspt(14.0));
        assertEquals("The server is not keeping up", found.get(0).title());

        for (final Diagnosis.Culprit c : found) assertFalse(c.evidence().contains("NaN"), c.title());
    }
}

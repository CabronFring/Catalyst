// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.libs;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LibraryManifestTest {
    @Test
    void everyEntryIsPinnedAndFetchedOverHttps() {
        for (final LibraryManifest.Artifact a : LibraryManifest.ALL) {
            assertTrue(a.sha256().matches("[0-9a-f]{64}"), a.fileName() + " has a malformed hash");
            assertTrue(a.url().startsWith("https://"), a.fileName() + " is not fetched over https");
            assertFalse(a.version().endsWith("-SNAPSHOT"), a.fileName() + " floats instead of pinning a build");
        }
    }

    @Test
    void noTwoEntriesShareAFileName() {
        final Set<String> seen = new HashSet<>();

        for (final LibraryManifest.Artifact a : LibraryManifest.ALL)
            assertTrue(seen.add(a.fileName()), "duplicate " + a.fileName());
    }

    @Test
    void timestampedSnapshotResolvesUnderItsSnapshotDirectory() {
        final var a = new LibraryManifest.Artifact("org.geysermc.mcprotocollib", "protocol",
                "1.21.11-20260512.221357-18", LibraryManifest.OPENCOLLAB, "0".repeat(64));
        assertEquals("https://repo.opencollab.dev/main/org/geysermc/mcprotocollib/protocol/1.21.11-SNAPSHOT/"
                + "protocol-1.21.11-20260512.221357-18.jar", a.url());
    }

    @Test
    void releaseResolvesUnderItsOwnVersion() {
        final var a = new LibraryManifest.Artifact("com.google.guava", "guava", "33.3.1-jre",
                LibraryManifest.CENTRAL, "0".repeat(64));
        assertEquals("https://repo1.maven.org/maven2/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar", a.url());
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
// Copied from TotemGuard (https://github.com/Bram1903/TotemGuard, GPL-3.0). Changed: the
// package, the metadata entry name, alerts log as warnings rather than severe, and a jar
// that cannot be read is reported plainly and allowed, since that is not a failed check.
/*
 * This file is part of TotemGuard - https://github.com/Bram1903/TotemGuard
 * Copyright (C) 2026 Bram and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package gg.catalyst.integrity;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarIntegrityChecker {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String INTEGRITY_ENTRY = "META-INF/catalyst/integrity.sha256";
    private static final String SEPARATOR =
            "======================================================================";

    private final Logger logger;
    private final String productName;

    public JarIntegrityChecker(@NotNull Logger logger, @NotNull String productName) {
        this.logger = logger;
        this.productName = productName;
    }

    public boolean verifyCurrentJar() {
        final Path jarPath = this.resolveCurrentJarPath();

        if (jarPath == null) {
            return true;
        }

        return this.verifyJar(jarPath);
    }

    public boolean verifyJar(@NotNull Path jarPath) {
        final String expectedFingerprint = this.readExpectedFingerprint(jarPath);

        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            this.logSuspiciousJar(
                    this.productName + " could not verify its embedded jar metadata.",
                    "This jar does not look like an original " + this.productName + " build.",
                    "The jar is missing its embedded integrity metadata.",
                    "This usually means the jar was rebuilt, unpacked, or modified."
            );
            return false;
        }

        try {
            final String actualFingerprint = this.computeFingerprint(jarPath);

            if (actualFingerprint.equalsIgnoreCase(expectedFingerprint)) {
                this.logger.info(this.productName + " integrity verified (" + jarPath.getFileName() + ").");
                return true;
            }

            this.logSuspiciousJar(
                    this.productName + " detected that the jar was modified since build.",
                    "This is most likely malware or direct jar tampering."
            );
            return false;
        } catch (IOException | NoSuchAlgorithmException exception) {
            // Not a failed check: the jar could not be read to check it. Nothing points to
            // tampering, so say so plainly and carry on rather than raising the alarm.
            this.logger.warning(this.productName + " could not check its jar integrity ("
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage() + "); starting anyway.");
            return true;
        }
    }

    private Path resolveCurrentJarPath() {
        final CodeSource codeSource = JarIntegrityChecker.class.getProtectionDomain().getCodeSource();

        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }

        try {
            final Path path = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();

            if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
                // Development classpath (classes on disk, not a jar), nothing to verify.
                return null;
            }
            return path;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private String readExpectedFingerprint(Path jarPath) {
        try (final ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            final ZipEntry entry = zipFile.getEntry(INTEGRITY_ENTRY);

            if (entry == null) {
                return null;
            }

            try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException exception) {
            return null;
        }
    }

    private String computeFingerprint(Path jarPath) throws IOException, NoSuchAlgorithmException {
        final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        final byte[] buffer = new byte[8192];

        try (final ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            final List<? extends ZipEntry> entries = zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !Objects.equals(entry.getName(), INTEGRITY_ENTRY))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();

            for (final ZipEntry entry : entries) {
                final byte[] nameBytes = entry.getName().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
                digest.update(nameBytes);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(entry.getSize()).array());

                try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                    int read;

                    while ((read = inputStream.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private void logSuspiciousJar(String headline, String assessment, String... details) {
        this.logger.warning(SEPARATOR);
        this.logger.warning(" " + this.productName.toUpperCase() + " SECURITY ALERT ");
        this.logger.warning(SEPARATOR);
        this.logger.warning("");
        this.logger.warning(" " + headline);
        this.logger.warning(" " + assessment);

        for (final String detail : details) {
            this.logger.warning(" " + detail);
        }

        this.logger.warning("");
        this.logger.warning(" Required action:");
        this.logger.warning(" 1. Delete this " + this.productName + " jar.");
        this.logger.warning(" 2. Reinstall " + this.productName + " from a trusted source.");
        this.logger.warning(" 3. If this warning appears again after reinstalling");
        this.logger.warning("    " + this.productName + ", malware is most likely modifying");
        this.logger.warning("    plugin jars or the server jar during startup.");
        this.logger.warning(" 4. Reinstall your server jar and every plugin");
        this.logger.warning("    from trusted sources.");
        this.logger.warning("");
        this.logger.warning(" " + this.productName + " has disabled itself.");
        this.logger.warning(SEPARATOR);
    }
}

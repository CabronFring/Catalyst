// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.libs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Fetches the jars in {@link LibraryManifest} into plugins/Catalyst/libs and checks each
 * one against its pinned SHA-256. A file that does not match is deleted, never used.
 *
 * Runs once, on a background thread; everything else asks {@link #state()} and treats
 * anything other than READY as "the libraries are not available".
 */
public final class LibraryDownloader {
    public enum State { NOT_STARTED, DISABLED, DOWNLOADING, READY, FAILED }

    /** Larger than any jar in the manifest by a wide margin; stops a bad mirror filling the disk. */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    /** A class from the bot client, loaded after download to prove the set is usable. */
    private static final String PROBE_CLASS = "org.geysermc.mcprotocollib.protocol.MinecraftProtocol";

    private final File dir;
    /** Replaced once at download time when libraries.via is "latest". */
    private volatile List<LibraryManifest.Artifact> artifacts;
    private final boolean latestVia;
    private final Logger log;
    private final String userAgent;

    private volatile State state = State.NOT_STARTED;
    private volatile String detail = "";

    public LibraryDownloader(File dir, List<LibraryManifest.Artifact> artifacts, String userAgent,
                             boolean latestVia, Logger log) {
        this.dir = dir;
        this.artifacts = List.copyOf(artifacts);
        this.userAgent = userAgent;
        this.latestVia = latestVia;
        this.log = log;
    }

    public State state() { return this.state; }
    public String detail() { return this.detail; }
    public boolean isReady() { return this.state == State.READY; }
    public boolean followsLatestVia() { return this.latestVia; }

    public void disable() {
        this.state = State.DISABLED;
        this.detail = "library downloads are switched off in config.yml (libraries.download)";
    }

    /** Blocking. Call from a background thread. Returns a one-line summary for the log. */
    public String run() {
        this.state = State.DOWNLOADING;

        try {
            Files.createDirectories(this.dir.toPath());
            final HttpClient http = HttpClient.newBuilder()

                    // NORMAL never follows an https -> http downgrade.
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            if (this.latestVia) this.artifacts = LatestVia.resolve(this.artifacts, http, this.userAgent, this.log);
            this.pruneUnlisted();

            int fetched = 0;

            for (final LibraryManifest.Artifact artifact : this.artifacts) {
                final File target = new File(this.dir, artifact.fileName());

                if (target.isFile() && artifact.sha256().equals(sha256(target))) continue;

                // Present but wrong: it may have been tampered with or half-written. Never reuse it.
                Files.deleteIfExists(target.toPath());
                this.download(http, artifact, target);
                fetched++;
            }

            try (final IsolatedLoader loader = IsolatedLoader.of(this.verifiedFiles())) {
                loader.check(PROBE_CLASS);

                // A newer Via release that does not fit the rest fails here, at startup.
                loader.check("com.viaversion.viaversion.api.Via");
                loader.check("net.raphimc.vialoader.ViaLoader");
            }

            this.state = State.READY;
            this.detail = this.artifacts.size() + " libraries verified"
                    + (fetched > 0 ? " (" + fetched + " downloaded)" : "");
        } catch (Throwable t) {
            this.state = State.FAILED;
            this.detail = t.getMessage() == null ? t.toString() : t.getMessage();
        }

        return this.detail;
    }

    /**
     * The manifest's files, re-hashed now rather than trusted from the last run, since they
     * sit on disk where anything else on the host can write. Throws if any fails.
     */
    public List<File> verifiedFiles() throws IOException {
        final List<File> files = new ArrayList<>(this.artifacts.size());

        for (final LibraryManifest.Artifact artifact : this.artifacts) {
            final File file = new File(this.dir, artifact.fileName());

            if (!file.isFile() || !artifact.sha256().equals(sha256(file)))
                throw new IOException(artifact.fileName() + " is missing or failed verification");
            files.add(file);
        }

        return files;
    }

    /**
     * Removes jars an older Catalyst pinned and this one no longer does, and any half-finished
     * download. They would never be loaded anyway, since classpaths come from the manifest.
     */
    private void pruneUnlisted() throws IOException {
        final Set<String> listed = new HashSet<>();

        for (final LibraryManifest.Artifact artifact : this.artifacts) listed.add(artifact.fileName());
        final File[] files = this.dir.listFiles((d, name) -> name.endsWith(".jar") || name.endsWith(".part"));

        if (files == null) return;

        for (final File f : files)
            if (!listed.contains(f.getName())) Files.deleteIfExists(f.toPath());
    }

    private void download(HttpClient http, LibraryManifest.Artifact artifact, File target)
            throws IOException, InterruptedException {
        final File part = new File(this.dir, artifact.fileName() + ".part");
        final HttpRequest request = HttpRequest.newBuilder(URI.create(artifact.url()))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", this.userAgent)
                .GET().build();

        final HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (final InputStream in = response.body()) {
            if (response.statusCode() != 200)
                throw new IOException(artifact.fileName() + ": HTTP " + response.statusCode() + " from " + artifact.url());

            final MessageDigest digest = newDigest();
            long total = 0;

            try (final OutputStream out = Files.newOutputStream(part.toPath())) {
                final byte[] buffer = new byte[64 * 1024];
                int n;

                while ((n = in.read(buffer)) != -1) {
                    total += n;

                    if (total > MAX_BYTES)
                        throw new IOException(artifact.fileName() + " is larger than any library should be");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }

            final String actual = HexFormat.of().formatHex(digest.digest());

            if (!actual.equals(artifact.sha256()))
                throw new IOException(artifact.fileName() + " failed verification (got " + actual
                        + ", expected " + artifact.sha256() + ") - refused");

            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part.toPath());
        }
    }

    static String sha256(File file) throws IOException {
        final MessageDigest digest = newDigest();

        try (final InputStream in = Files.newInputStream(file.toPath())) {
            final byte[] buffer = new byte[64 * 1024];
            int n;

            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM ships SHA-256", e);
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;

/**
 * Uploads a saved {@code .sparkprofile} to spark's own storage so it can be viewed at
 * spark.lucko.me, the same route spark takes when you let it upload. Opt-in, because it sends
 * the profile off-server. Any failure returns empty and the caller keeps the local-file wording,
 * so a change on spark's side can never break the report.
 */
public final class SparkUpload {

    // spark's public infrastructure: profiles POST here, and the viewer reads them from there.
    private static final String BYTEBIN = "https://spark-usercontent.lucko.me/post";
    private static final String VIEWER = "https://spark.lucko.me/";
    private static final String SAMPLER_TYPE = "application/x-spark-sampler";

    private SparkUpload() {}

    /** The shareable spark.lucko.me link for this profile, or empty if the upload did not work. */
    public static Optional<String> upload(File profile) {
        try {
            final byte[] body = Files.readAllBytes(profile.toPath());
            final boolean gzipped = body.length > 1 && body[0] == 0x1f && body[1] == (byte) 0x8b;

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BYTEBIN))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", SAMPLER_TYPE)
                    .header("User-Agent", "Catalyst")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (gzipped) request = request.header("Content-Encoding", "gzip");

            final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            final HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() / 100 != 2) return Optional.empty();

            // Bytebin returns the key in the Location header, e.g. "/aBcDeF". Keep only the key
            // itself: trim it, drop the leading slash, and cut at the first character that cannot
            // be in a key, so a stray bracket or whitespace never ends up in the viewer URL (a URL
            // with an illegal character throws when it is turned into a clickable chat component).
            final String key = response.headers().firstValue("Location")
                    .map(s -> s.trim().replaceAll("^/", "").replaceAll("[^A-Za-z0-9_-].*$", ""))
                    .orElse("");
            return key.isBlank() ? Optional.empty() : Optional.of(VIEWER + key);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

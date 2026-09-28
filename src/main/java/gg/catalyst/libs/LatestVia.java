// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.libs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in (libraries.via: latest): swaps the pinned ViaVersion/ViaBackwards/ViaLoader for the newest
 * release in their repository, so a new Minecraft version is supported without a Catalyst update.
 * The trade-off (in config.yml): a pinned jar is checked against a hash built into Catalyst, a newer
 * one only against the hash its repository publishes - which catches a corrupt download, not a
 * compromised repo. Only these three follow latest; anything unresolved falls back to the pinned version.
 */
final class LatestVia {
    /** Only the translation libraries themselves, which is what changes with each game release. */
    static final Set<String> FOLLOWED = Set.of("ViaLoader", "viaversion-common", "viabackwards-common");

    /** Release numbers only: they end up in a URL and a file name. */
    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.\\-]{0,40}");
    private static final Pattern RELEASE = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern SHA256 = Pattern.compile("^([0-9a-fA-F]{64})\\b");

    private LatestVia() {}

    static List<LibraryManifest.Artifact> resolve(List<LibraryManifest.Artifact> pinned, HttpClient http,
                                                  String userAgent, Logger log) {
        final List<LibraryManifest.Artifact> out = new ArrayList<>(pinned.size());

        for (final LibraryManifest.Artifact a : pinned) {
            if (!FOLLOWED.contains(a.name())) {
                out.add(a);
                continue;
            }

            try {
                final String base = a.repository() + "/" + a.group().replace('.', '/') + "/" + a.name();
                final String latest = matchOne(RELEASE, fetch(http, base + "/maven-metadata.xml", userAgent));

                if (latest == null || !VERSION.matcher(latest).matches()) throw new IllegalStateException("no release listed");

                if (latest.equals(a.version())) {
                    out.add(a); // the pinned build: keep its built-in hash
                    continue;
                }
                final String sha = matchOne(SHA256, fetch(http, base + "/" + latest + "/" + a.name() + "-" + latest + ".jar.sha256", userAgent).trim());

                if (sha == null) throw new IllegalStateException("no SHA-256 published");
                out.add(new LibraryManifest.Artifact(a.group(), a.name(), latest, a.repository(), sha.toLowerCase()));
                log.info(a.name() + " " + latest + " (latest; checked against the repository's published SHA-256)");
            } catch (Exception e) {
                log.warning("Could not resolve the latest " + a.name() + " (" + e.getMessage() + "); using pinned " + a.version());
                out.add(a);
            }
        }

        return out;
    }

    private static String fetch(HttpClient http, String url, String userAgent) throws Exception {
        final HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " for " + url);

        return r.body();
    }

    static String matchOne(Pattern p, String text) {
        final Matcher m = p.matcher(text);

        return m.find() ? m.group(1) : null;
    }
}

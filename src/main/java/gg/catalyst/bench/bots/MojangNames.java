// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import com.fasterxml.jackson.databind.JsonNode;
import gg.catalyst.util.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Asks Mojang which real accounts currently own a set of names. On an online-mode server the
 * server's own name cache only remembers the most recent players (spigot.yml user-cache-size),
 * so it cannot say for certain that no real player called cat_bot_N has been here; Mojang can
 * say who owns the name, and the server's player files whether that account has played.
 */
final class MojangNames {
    private static final String BULK = "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname";
    /** The most names Mojang accepts in one request. */
    private static final int BATCH = 10;

    private MojangNames() {}

    /** Name (lower case) to the UUID of the real account holding it; names nobody owns are absent. Blocking. */
    static Map<String, UUID> owners(Collection<String> names, String userAgent) throws IOException, InterruptedException {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        final List<String> all = new ArrayList<>(names);
        final Map<String, UUID> owners = new LinkedHashMap<>();

        for (int i = 0; i < all.size(); i += BATCH) {
            final List<String> batch = all.subList(i, Math.min(all.size(), i + BATCH));
            final String body = JsonUtil.MAPPER.writeValueAsString(batch);
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(BULK))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                throw new IOException("Mojang answered HTTP " + response.statusCode());

            for (final JsonNode profile : JsonUtil.MAPPER.readTree(response.body()))
                owners.put(profile.path("name").asText().toLowerCase(Locale.ROOT), parse(profile.path("id").asText()));
        }

        return owners;
    }

    /** A skin as Mojang signed it: the textures property of an account's profile. */
    record Skin(String owner, String value, String signature) {}

    private static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /**
     * The current signed skin of each named account. Clients only show a skin Mojang has
     * signed, so it has to come from Mojang like this rather than from an image file. Names
     * nobody owns are skipped. Blocking.
     */
    static List<Skin> skins(Collection<String> names, String userAgent) throws IOException, InterruptedException {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        final List<Skin> skins = new ArrayList<>();

        for (final Map.Entry<String, UUID> owner : owners(names, userAgent).entrySet()) {
            final String id = owner.getValue().toString().replace("-", "");
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(PROFILE + id + "?unsigned=false"))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", userAgent).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) continue;

            for (final JsonNode property : JsonUtil.MAPPER.readTree(response.body()).path("properties")) {
                final String value = property.path("value").asText(""), signature = property.path("signature").asText("");

                if (property.path("name").asText().equals("textures") && !value.isEmpty() && !signature.isEmpty())
                    skins.add(new Skin(owner.getKey(), value, signature));
            }
        }

        return skins;
    }

    /** Mojang writes UUIDs without dashes. */
    static UUID parse(String id) {
        if (!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("not a Mojang UUID: " + id);

        return new UUID(Long.parseUnsignedLong(id.substring(0, 16), 16), Long.parseUnsignedLong(id.substring(16), 16));
    }
}

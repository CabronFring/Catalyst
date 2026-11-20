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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    /** How long a lookup keeps retrying when Mojang rate-limits it or is briefly down. */
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    private MojangNames() {}

    /**
     * How long the bot-name check keeps retrying: the bots wait 12 seconds for it, so it has to
     * give its answer inside that.
     */
    private static final Duration OWNER_PATIENCE = Duration.ofSeconds(10);
    /** Who owns a name changes rarely; asking again for every start only gets rate-limited. */
    private static final Duration OWNER_KEEP = Duration.ofMinutes(10);
    /** Name (lower case) to its owner, or empty when nobody owns it, with when Mojang said so. */
    private static final Map<String, Map.Entry<Optional<UUID>, Long>> OWNER_CACHE = new ConcurrentHashMap<>();

    /** Name (lower case) to the UUID of the real account holding it; names nobody owns are absent. Blocking. */
    static Map<String, UUID> owners(Collection<String> names, String userAgent) throws IOException, InterruptedException {
        final long now = System.currentTimeMillis();
        final Map<String, UUID> owners = new LinkedHashMap<>();
        final List<String> toAsk = new ArrayList<>();

        for (final String name : names) {
            final String key = name.toLowerCase(Locale.ROOT);
            final Map.Entry<Optional<UUID>, Long> cached = OWNER_CACHE.get(key);

            if (cached == null || now - cached.getValue() >= OWNER_KEEP.toMillis()) toAsk.add(key);
            else cached.getKey().ifPresent(id -> owners.put(key, id));
        }

        if (toAsk.isEmpty()) return owners;
        final Map<String, UUID> asked = owners(toAsk, userAgent, client(), System.currentTimeMillis() + OWNER_PATIENCE.toMillis());

        for (final String key : toAsk)
            OWNER_CACHE.put(key, Map.entry(Optional.ofNullable(asked.get(key)), now));
        owners.putAll(asked);

        return owners;
    }

    private static Map<String, UUID> owners(Collection<String> names, String userAgent, HttpClient http, long deadline)
            throws IOException, InterruptedException {
        final List<String> all = new ArrayList<>(names);
        final Map<String, UUID> owners = new LinkedHashMap<>();

        for (int i = 0; i < all.size(); i += BATCH) {
            final List<String> batch = all.subList(i, Math.min(all.size(), i + BATCH));
            final String body = JsonUtil.MAPPER.writeValueAsString(batch);
            final HttpResponse<String> response = send(http, HttpRequest.newBuilder(URI.create(BULK))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), deadline);

            if (response.statusCode() != 200)
                throw new IOException("Mojang answered HTTP " + response.statusCode());

            for (final JsonNode profile : JsonUtil.MAPPER.readTree(response.body()))
                owners.put(profile.path("name").asText().toLowerCase(Locale.ROOT), parse(profile.path("id").asText()));
        }

        return owners;
    }

    /** A skin as Mojang signed it: the textures property of an account's profile. */
    record Skin(String owner, String value, String signature) {}

    /**
     * What a skin lookup came to: the skins found, the names Mojang says have no account or no
     * skin, and the names it could not answer for in time (rate limits, outages).
     */
    record SkinLookup(List<Skin> found, List<String> missing, List<String> failed) {}

    private static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /**
     * Mojang's answers, kept between runs. Skins hardly ever change and their signatures do not
     * expire, while asking again for the same profiles minutes later is what gets rate-limited.
     */
    private static final Duration KEEP = Duration.ofHours(6);
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /** A cached answer: the skin, or empty when the name has no account or no skin. */
    private record Cached(Optional<Skin> skin, long at) {
        boolean fresh() { return System.currentTimeMillis() - this.at < KEEP.toMillis(); }
    }

    /**
     * The current signed skin of each named account. Clients only show a skin Mojang has
     * signed, so it has to come from Mojang like this rather than from an image file. Each skin
     * is handed to {@code onFound} as soon as it is known, so bots can wear it before the rest
     * arrive. Rate-limited lookups are retried for a while before being given up on. Blocking.
     */
    static SkinLookup skins(Collection<String> names, String userAgent, Consumer<Skin> onFound) throws InterruptedException {
        final List<Skin> found = new ArrayList<>();
        final List<String> missing = new ArrayList<>(), failed = new ArrayList<>(), toFetch = new ArrayList<>();
        final Map<String, String> asWritten = new HashMap<>();

        for (final String name : names) {
            final String key = name.toLowerCase(Locale.ROOT);
            final Cached cached = CACHE.get(key);
            asWritten.put(key, name);

            if (cached == null || !cached.fresh()) toFetch.add(key);
            else if (cached.skin().isPresent()) {
                found.add(cached.skin().get());
                onFound.accept(cached.skin().get());
            } else missing.add(name);
        }

        if (toFetch.isEmpty()) return new SkinLookup(found, missing, failed);

        final HttpClient http = client();
        final long deadline = deadline();
        final Map<String, UUID> owners;

        try {
            owners = owners(toFetch, userAgent, http, deadline);
        } catch (IOException | RuntimeException e) {
            toFetch.forEach(key -> failed.add(asWritten.get(key)));
            return new SkinLookup(found, missing, failed);
        }

        for (final String key : toFetch) {
            final UUID id = owners.get(key);

            if (id == null) {
                CACHE.put(key, new Cached(Optional.empty(), System.currentTimeMillis()));
                missing.add(asWritten.get(key));
                continue;
            }

            final Optional<Skin> skin;
            try {
                skin = profileSkin(http, key, id, userAgent, deadline);
            } catch (IOException | RuntimeException e) {
                failed.add(asWritten.get(key));
                continue;
            }

            CACHE.put(key, new Cached(skin, System.currentTimeMillis()));
            if (skin.isPresent()) {
                found.add(skin.get());
                onFound.accept(skin.get());
            } else missing.add(asWritten.get(key));
        }

        return new SkinLookup(found, missing, failed);
    }

    /** The account's signed skin, empty when it has none; throws when Mojang would not say. */
    private static Optional<Skin> profileSkin(HttpClient http, String name, UUID id, String userAgent, long deadline)
            throws IOException, InterruptedException {
        final HttpResponse<String> response = send(http, HttpRequest.newBuilder(
                        URI.create(PROFILE + id.toString().replace("-", "") + "?unsigned=false"))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", userAgent).GET().build(), deadline);

        // 204 and 404: no such profile any more.
        if (response.statusCode() == 204 || response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IOException("Mojang answered HTTP " + response.statusCode());

        for (final JsonNode property : JsonUtil.MAPPER.readTree(response.body()).path("properties")) {
            final String value = property.path("value").asText(""), signature = property.path("signature").asText("");

            if (property.path("name").asText().equals("textures") && !value.isEmpty() && !signature.isEmpty())
                return Optional.of(new Skin(name, value, signature));
        }

        return Optional.empty();
    }

    /**
     * Sends a request, waiting and trying again while Mojang rate-limits it (429), is briefly
     * down (5xx) or cannot be reached, until the deadline. Returns the last answer it got.
     */
    private static HttpResponse<String> send(HttpClient http, HttpRequest request, long deadline)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            final HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (!pause(backoff(attempt), deadline)) throw e;
                continue;
            }

            final int status = response.statusCode();
            if (status != 429 && status < 500) return response;

            final long wait = response.headers().firstValue("Retry-After")
                    .flatMap(MojangNames::seconds).map(s -> s * 1000).orElse(backoff(attempt));
            if (!pause(wait, deadline)) return response;
        }
    }

    /** 1, 2, 4, 8 s, then every 10 s. */
    private static long backoff(int attempt) { return Math.min(1000L << Math.min(attempt, 4), 10_000L); }

    /** Sleeps for the wait if that still ends before the deadline; false when it would not. */
    private static boolean pause(long wait, long deadline) throws InterruptedException {
        if (System.currentTimeMillis() + wait > deadline) return false;
        Thread.sleep(wait);
        return true;
    }

    private static Optional<Long> seconds(String header) {
        try {
            return Optional.of(Math.max(0, Long.parseLong(header.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static HttpClient client() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); }

    private static long deadline() { return System.currentTimeMillis() + PATIENCE.toMillis(); }

    /** Mojang writes UUIDs without dashes. */
    static UUID parse(String id) {
        if (!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("not a Mojang UUID: " + id);

        return new UUID(Long.parseUnsignedLong(id.substring(0, 16), 16), Long.parseUnsignedLong(id.substring(16), 16));
    }
}

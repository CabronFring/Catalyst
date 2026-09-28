// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.upload;

import com.fasterxml.jackson.databind.JsonNode;
import gg.catalyst.util.JsonUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experimental. Sends a report file to an HTTP endpoint described entirely in config - host,
 * method, headers and form fields are all arbitrary, so nothing about the destination, including
 * whether it authenticates at all, is baked in. Describing the request, not a service, means it
 * outlives whatever endpoint it was first pointed at.
 */
public final class ReportUploader {

    // Stops at quotes and angle brackets so a link inside an anchor tag does not swallow the markup.
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s\"'<>\\\\]+");

    /** Java's HttpClient refuses to let callers set these. */
    private static final List<String> RESTRICTED =
            List.of("connection", "content-length", "expect", "host", "upgrade");

    private boolean enabled;
    private boolean debug;
    private String endpoint;
    private String method;
    private String fileField;
    private String responseUrlPath;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> fields = new LinkedHashMap<>();
    private Logger logger = Logger.getLogger("Catalyst");

    public void configure(FileConfiguration cfg, Logger log) {
        this.logger = log;
        this.enabled = cfg.getBoolean("experimental.upload.enabled", false);
        this.debug = cfg.getBoolean("experimental.upload.debug", false);
        this.endpoint = cfg.getString("experimental.upload.request.endpoint", "");
        this.method = cfg.getString("experimental.upload.request.method", "POST").toUpperCase();
        this.fileField = cfg.getString("experimental.upload.request.file-field", "file");
        this.responseUrlPath = cfg.getString("experimental.upload.request.response-url-path", "");

        readMap(cfg, "experimental.upload.request.headers", this.headers);
        readMap(cfg, "experimental.upload.request.fields", this.fields);
    }

    private static void readMap(FileConfiguration cfg, String path, Map<String, String> into) {
        into.clear();
        final ConfigurationSection section = cfg.getConfigurationSection(path);

        if (section == null) return;

        for (final String key : section.getKeys(false))
            into.put(key, String.valueOf(section.get(key)));
    }

    public boolean isEnabled() { return this.enabled; }

    /** Every failure passes through here so debug mode can log it with its cause. */
    private UploadResult fail(String message, Throwable cause) {
        if (this.debug) this.logger.log(Level.WARNING, "Upload failed: " + message, cause);

        return UploadResult.fail(message);
    }

    /** The only thing genuinely required is somewhere to send it. */
    public boolean isConfigured() {
        return this.endpoint != null && !this.endpoint.isBlank();
    }

    public UploadResult upload(String fileName, String content) {
        return this.uploadBytes(fileName, content.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    /** Uploads a file from disk, for reports that are images rather than text. */
    public UploadResult uploadFile(String fileName, File file, String contentType) {
        byte[] bytes;

        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            return this.fail("Could not read " + file.getName() + ": " + e.getMessage(), e);
        }

        return this.uploadBytes(fileName, bytes, contentType);
    }

    private UploadResult uploadBytes(String fileName, byte[] content, String contentType) {
        if (!this.enabled) return UploadResult.fail("Uploading is disabled in config.yml.");

        if (!this.isConfigured()) return UploadResult.fail("Set experimental.upload.request.endpoint first.");

        final String boundary = "CatalystBoundary" + System.nanoTime();
        byte[] body;

        try {
            body = this.multipartBody(boundary, fileName, content, contentType);
        } catch (IOException e) {
            return this.fail("Could not build the upload body: " + e.getMessage(), e);
        }

        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(this.endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .method(this.method, HttpRequest.BodyPublishers.ofByteArray(body));

            for (final Map.Entry<String, String> header : this.headers.entrySet()) {
                final String name = header.getKey();

                // Content-Type carries the multipart boundary, so it is ours to set.
                if (name.equalsIgnoreCase("content-type")) continue;

                if (RESTRICTED.contains(name.toLowerCase())) continue;
                builder.header(name, header.getValue());
            }
            builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);

            final HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            final HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                if (this.debug) this.logger.warning("Upload reply body (" + response.statusCode() + "): " + response.body());
                return this.fail("Server replied " + response.statusCode() + ".", null);
            }

            final List<String> urls = extractUrls(response.body(), this.responseUrlPath);

            if (urls.isEmpty()) {
                // A 2xx only means the request was accepted; many endpoints report a rejected
                // upload in a 200 body, so don't claim success - show the reply and the likely cause.
                final String where = this.responseUrlPath.isBlank()
                        ? "anywhere in the reply"
                        : "at response-url-path '" + this.responseUrlPath + "'";

                if (this.debug) this.logger.warning("Upload reply body: " + response.body());
                return this.fail("Endpoint replied " + response.statusCode()
                        + " but no link was found " + where + ". It said: " + preview(response.body()), null);
            }
            return UploadResult.ok(urls);

        } catch (IllegalArgumentException e) {
            return this.fail("Bad request settings: " + e.getMessage(), e);
        } catch (IOException e) {
            return this.fail("Could not reach the endpoint: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return this.fail("Upload was interrupted.", e);
        }
    }

    private byte[] multipartBody(String boundary, String fileName, byte[] content, String contentType)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final String crlf = "\r\n";

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + this.fileField
                 + "\"; filename=\"" + fileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        for (final Map.Entry<String, String> field : this.fields.entrySet())
            writeField(out, boundary, field.getKey(), field.getValue());

        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));

        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws IOException {
        final String crlf = "\r\n";
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
    }

    /** First slice of a reply, so a misconfigured path can be diagnosed from chat. */
    private static String preview(String body) {
        if (body == null) return "(empty)";
        final String flat = body.replaceAll("\\s+", " ").trim();

        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }

    /** The URLs in the response, each cleaned of a glued-on base address. Package-visible for testing. */
    static List<String> extractUrls(String body, String responseUrlPath) {
        return findUrls(body, responseUrlPath).stream().map(ReportUploader::unjoin).toList();
    }

    /**
     * Some endpoints prefix their own base address onto a link that is already absolute,
     * giving https://site/https://files/x. A scheme inside the path (not the query, where
     * ?redirect=https://... is legitimate) can only come from that, so the inner URL is the real one.
     */
    static String unjoin(String url) {
        final int query = url.indexOf('?');
        final String path = query < 0 ? url : url.substring(0, query);
        final int inner = Math.max(path.lastIndexOf("https://"), path.lastIndexOf("http://"));

        return inner > 0 ? url.substring(inner) : url;
    }

    private static List<String> findUrls(String body, String responseUrlPath) {
        // Most precise first, so a wrong path or unexpected shape degrades to a worse answer, not none.
        JsonNode root = null;

        try {
            root = JsonUtil.MAPPER.readTree(body);
        } catch (IOException notJson) {
            // Falls through to the text scan below.
        }

        if (root != null && !responseUrlPath.isBlank()) {
            final List<String> atPath = new ArrayList<>();
            collectUrls(root.at(toPointer(responseUrlPath)), atPath);

            if (!atPath.isEmpty()) return atPath;
        }

        if (root != null) {
            final List<String> anywhere = new ArrayList<>();
            collectUrls(root, anywhere);

            if (!anywhere.isEmpty()) return anywhere;
        }

        // Last resort: the endpoint may reply in plain text, or in JSON that hides the
        // link somewhere the tree walk cannot see, such as inside an escaped string.
        final List<String> scanned = new ArrayList<>();
        final Matcher m = BARE_URL.matcher(body);

        while (m.find()) scanned.add(trimTrailingPunctuation(m.group()));

        return scanned;
    }

    /** A URL at the end of a sentence or inside JSON picks up stray characters. */
    private static String trimTrailingPunctuation(String url) {
        return url.replaceAll("[\"',.;)\\]}]+$", "");
    }

    /** Accepts either dotted notation ("result.urls") or a JSON Pointer ("/result/urls"). */
    private static String toPointer(String path) {
        final String trimmed = path.trim();

        if (trimmed.startsWith("/")) return trimmed;

        return "/" + trimmed.replace('.', '/');
    }

    private static void collectUrls(JsonNode node, List<String> into) {
        if (node.isTextual()) {
            final String text = node.asText();

            if (text.startsWith("http://") || text.startsWith("https://")) into.add(text);
            return;
        }

        for (final JsonNode child : node) collectUrls(child, into);
    }
}

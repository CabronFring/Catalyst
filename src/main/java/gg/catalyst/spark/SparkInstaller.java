// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import com.fasterxml.jackson.databind.JsonNode;
import gg.catalyst.Catalyst;
import gg.catalyst.platform.PlatformDetector;
import gg.catalyst.util.JsonUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads the latest spark build for this platform into plugins/ and loads it, so
 * perf profile and perf stats work without a restart.
 *
 * The sources are fixed; nothing about them can be configured or passed in:
 *   - Spigot, CraftBukkit (and Paper without its bundled copy): spark's own download
 *     API, which names the current build and its SHA-1. The download is checked against it.
 *   - Folia: the newest successful build of spark-extra-platforms on spark's CI. That job
 *     publishes no hash, so only HTTPS vouches for the file, and the command says so.
 * Either way, only a jar from ci.lucko.me whose plugin.yml names itself "spark" is kept.
 */
public final class SparkInstaller {
    private static final String BUKKIT_API = "https://sparkapi.lucko.me/download";
    private static final String FOLIA_JOB = "https://ci.lucko.me/job/spark-extra-platforms/";
    private static final String TRUSTED_PREFIX = "https://ci.lucko.me/job/";
    private static final Pattern BUKKIT_FILE = Pattern.compile("spark-[0-9A-Za-z.\\-]{1,60}-bukkit\\.jar");
    private static final Pattern FOLIA_PATH = Pattern.compile("spark-folia/build/libs/(spark-[0-9A-Za-z.\\-]{1,60}-folia\\.jar)");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private static final AtomicBoolean running = new AtomicBoolean();

    private SparkInstaller() {}

    /** Where the jar would come from on this server, for the status line. */
    public static String sourceDescription() {
        return PlatformDetector.isFolia()
                ? "the Folia build (spark-folia) from ci.lucko.me, checked by HTTPS only"
                : "the Bukkit build from spark's download API, checked against its SHA-1";
    }

    /** Why spark cannot or need not be installed, or null when it can. */
    public static String refusal(Catalyst plugin) {
        if (SparkSupport.isAvailable()) return "spark is already running on this server.";
        final Plugin existing = Bukkit.getPluginManager().getPlugin("spark");

        if (existing != null)
            return "spark is loaded but not running: it failed to start (see the console). A restart clears it.";
        final File[] jars = pluginsFolder(plugin).listFiles((d, n) -> n.toLowerCase().startsWith("spark") && n.endsWith(".jar"));

        if (jars != null && jars.length > 0)
            return jars[0].getName() + " is already in plugins/ but not loaded. Restart the server to load it.";

        return null;
    }

    public static void install(Catalyst plugin, CommandSender sender) {
        final String refusal = refusal(plugin);

        if (refusal != null) {
            sender.sendMessage(ChatColor.YELLOW + refusal);
            return;
        }

        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(ChatColor.RED + "spark is already being installed.");
            return;
        }
        final boolean folia = PlatformDetector.isFolia();
        sender.sendMessage(ChatColor.GRAY + "Downloading " + sourceDescription() + "...");
        plugin.runAsync(() -> {
            File jar;

            try {
                jar = download(plugin, folia);
            } catch (Exception e) {
                running.set(false);
                sender.sendMessage(ChatColor.RED + "Could not install spark: " + e.getMessage());
                return;
            }
            plugin.runGlobal(() -> {
                try {
                    load(plugin, sender, jar, folia);
                } finally {
                    running.set(false);
                }
            });
        });
    }

    @SuppressWarnings("deprecation") // getDescription(): its replacement is Paper-only
    private static File download(Catalyst plugin, boolean folia) throws Exception {
        final HttpClient http = HttpClient.newBuilder()

                // NORMAL never follows an https -> http downgrade.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        final String userAgent = plugin.getName() + "/" + plugin.getDescription().getVersion();

        String fileName, url, sha1;

        if (folia) {
            // The brackets are Jenkins' tree syntax, escaped for a URI.
            final JsonNode build = JsonUtil.MAPPER.readTree(fetch(http, FOLIA_JOB
                    + "lastSuccessfulBuild/api/json?tree=number,artifacts%5BrelativePath%5D", userAgent));
            final int number = build.path("number").asInt(-1);
            String path = null;

            for (final JsonNode a : build.path("artifacts"))
                if (FOLIA_PATH.matcher(a.path("relativePath").asText()).matches()) path = a.path("relativePath").asText();

            if (number < 1 || path == null) throw new IOException("spark's CI lists no Folia build right now");
            final var m = FOLIA_PATH.matcher(path);
            m.matches();
            fileName = m.group(1);
            url = FOLIA_JOB + number + "/artifact/" + path;
            sha1 = null;
        } else {
            final JsonNode bukkit = JsonUtil.MAPPER.readTree(fetch(http, BUKKIT_API, userAgent)).path("bukkit");
            fileName = bukkit.path("fileName").asText();
            url = bukkit.path("url").asText();
            sha1 = bukkit.path("sha1").asText().toLowerCase();

            if (!BUKKIT_FILE.matcher(fileName).matches() || !SHA1.matcher(sha1).matches())
                throw new IOException("spark's download API gave an answer this version does not understand");
        }

        if (!url.startsWith(TRUSTED_PREFIX))
            throw new IOException("the download pointed away from ci.lucko.me (" + url + "), refused");

        final File target = new File(pluginsFolder(plugin), fileName);
        final File part = new File(pluginsFolder(plugin), fileName + ".part");

        try {
            final HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", userAgent).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");

            try (final InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + url);
                long total = 0;

                try (final OutputStream out = Files.newOutputStream(part.toPath())) {
                    final byte[] buffer = new byte[64 * 1024];
                    int n;

                    while ((n = in.read(buffer)) != -1) {
                        total += n;

                        if (total > MAX_BYTES) throw new IOException(fileName + " is far larger than spark is");
                        digest.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
            }

            if (sha1 != null) {
                final String actual = HexFormat.of().formatHex(digest.digest());

                if (!actual.equals(sha1))
                    throw new IOException(fileName + " failed verification (got " + actual + ", expected " + sha1 + "), refused");
            }

            if (!namesItselfSpark(part))
                throw new IOException(fileName + " is not the spark plugin, refused");
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(part.toPath());
        }
    }

    /** Loads and enables the new jar, as a server would at startup. */
    private static void load(Catalyst plugin, CommandSender sender, File jar, boolean folia) {
        final PluginManager pm = Bukkit.getPluginManager();
        Plugin spark = null;

        try {
            // spark defines no onLoad, so loading and enabling is all it needs.
            spark = pm.loadPlugin(jar);

            if (spark == null) throw new IllegalStateException("the server did not load it");
            pm.enablePlugin(spark);
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.YELLOW + jar.getName() + " is in plugins/ but could not be loaded now ("
                    + t.getMessage() + "). It will load at the next restart.");
            return;
        }

        // isEnabled() is not proof: a plugin whose onEnable throws stays flagged as enabled.
        // spark's API answering is.
        if (!SparkSupport.isAvailable()) {
            try {
                pm.disablePlugin(spark);
            } catch (Throwable ignored) {
                // It never started properly; nothing to stop.
            }
            final boolean removed = jar.delete();
            sender.sendMessage(ChatColor.RED + "spark failed to start (see the console)"
                    + (removed ? ", so it was removed again." : ". Delete " + jar.getName() + " from plugins/."));

            // A known spark bug rather than anything about this server: spark means to run
            // without Spigot, but its check for Player.Spigot catches Exception, and a missing
            // class throws NoClassDefFoundError. Say so, since the console only shows the trace.
            if (!folia && !classExists("org.bukkit.entity.Player$Spigot"))
                sender.sendMessage(ChatColor.GRAY + "On plain CraftBukkit this is a spark bug (as of 1.10.187): it looks up"
                        + " Spigot's Player.Spigot class without handling its absence. A later spark may fix it.");
            return;
        }

        // Players already online would not see /spark in their command list until they rejoin.
        for (final Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
        sender.sendMessage(ChatColor.GREEN + "Installed and loaded " + jar.getName() + (folia ? " (not hash-checked: spark's CI"
                + " publishes none for Folia builds)." : ", verified against spark's SHA-1."));
        sender.sendMessage(ChatColor.GRAY + "/catalyst perf profile and /catalyst perf stats now use it.");
    }

    private static boolean namesItselfSpark(File jar) {
        try (final ZipFile zip = new ZipFile(jar)) {
            final ZipEntry entry = zip.getEntry("plugin.yml");

            if (entry == null) return false;

            try (final InputStreamReader in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return "spark".equals(YamlConfiguration.loadConfiguration(in).getString("name"));
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static String fetch(HttpClient http, String url, String userAgent) throws IOException, InterruptedException {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + url);

        return response.body();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, SparkInstaller.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static File pluginsFolder(Catalyst plugin) {
        return plugin.getDataFolder().getParentFile();
    }
}

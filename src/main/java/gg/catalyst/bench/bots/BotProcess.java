// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import gg.catalyst.util.Branding;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs the bot client (BotMain) in a separate JVM, so the bots' own CPU time lands on
 * another process and is never mistaken for server load.
 *
 * The child's classpath is exactly Catalyst's jar plus the hash-verified library files,
 * listed one by one; nothing else in the libs folder is ever picked up. It is given only
 * a port, a bot count, a duration and the server's version, plus two lines on stdin (how
 * to pass proxy forwarding, and the stage's identity token) - there is no address to give,
 * as BotMain can only connect to loopback.
 */
public final class BotProcess {
    /**
     * An absolute ceiling on bots, a safety bound against a typo or abuse - not the everyday
     * limit, which is benchmark.bots.max-count (default 100) and read through BotStage.maxBots.
     * The memory guard stops a run before it can exhaust the server whatever this is.
     */
    public static final int MAX_BOTS = 200;
    private static final String MAIN_CLASS = "gg.catalyst.bench.bots.BotMain";

    private final Process process;
    private final Logger logger;
    private volatile int connected, chunks, peakConnected;
    private volatile String client = "";
    private final List<String> departures = new CopyOnWriteArrayList<>();
    /** Roaming bots that have finished their route, or been knocked off it, and want another. */
    private final Set<String> idle = ConcurrentHashMap.newKeySet();

    private BotProcess(Process process, Logger logger) {
        this.process = process;
        this.logger = logger;
    }

    /** @param roam whether the bots follow routes sent with {@link #sendRoute} instead of walking lanes */
    public static BotProcess launch(File workDir, List<File> verifiedJars, int port, int count,
                                    int seconds, String serverVersion, String namePrefix, String forwardingLine,
                                    String identityHost, boolean roam, Logger logger) throws IOException {
        if (count < 1 || count > MAX_BOTS) throw new IllegalArgumentException("bot count must be 1-" + MAX_BOTS);

        final List<String> classpath = new ArrayList<>();
        classpath.add(ownJar().getAbsolutePath());

        for (final File jar : verifiedJars) classpath.add(jar.getAbsolutePath());

        final List<String> command = new ArrayList<>(List.of(
                javaExecutable(),

                // Sized to the bot count and single-collector: in a container this process
                // shares the server's memory limit, so it takes no more than it needs.
                "-Xmx" + ContainerLimits.mib(ContainerLimits.botHeapBytes(count)) + "m", "-XX:+UseSerialGC",
                "-cp", String.join(File.pathSeparator, classpath),
                MAIN_CLASS,
                Integer.toString(port), Integer.toString(count), Integer.toString(seconds), serverVersion, namePrefix));

        if (roam) command.add("roam");

        if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("cannot create " + workDir);
        final Process process = new ProcessBuilder(command)
                .directory(workDir)
                .redirectError(new File(workDir, "bot-process.log"))
                .start();

        // First lines on stdin, never arguments: a Velocity secret and the stage's identity
        // token must not appear in the process list, where anyone on the host can read them.
        final OutputStream stdin = process.getOutputStream();
        stdin.write((forwardingLine + "\n" + "IDENTITY " + identityHost + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();

        final BotProcess bots = new BotProcess(process, logger);
        final Thread reader = new Thread(bots::readOutput, "catalyst-bot-output");
        reader.setDaemon(true);
        reader.start();

        return bots;
    }

    private void readOutput() {
        try (final BufferedReader in = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;

            while ((line = in.readLine()) != null) {
                if (line.startsWith("STATUS ") || line.startsWith("DONE ")) this.parseStatus(line);
                else if (line.startsWith("CLIENT ")) this.client = line.substring(7);
                else if (line.startsWith("LEFT ")) this.departures.add(line.substring(5));
                else if (line.startsWith("IDLE ")) this.idle.add(line.substring(5).trim());
            }
        } catch (IOException ignored) {
            // Process ended.
        }
    }

    private void parseStatus(String line) {
        for (final String part : line.split(" ")) {
            try {
                if (part.startsWith("connected=")) {
                    this.connected = Integer.parseInt(part.substring(10));
                    this.peakConnected = Math.max(this.peakConnected, this.connected);
                }
                else if (part.startsWith("chunks=")) this.chunks = Integer.parseInt(part.substring(7));
            } catch (NumberFormatException ignored) {
                // Malformed line; keep the last good figure.
            }
        }
    }

    public int connected() { return this.connected; }
    public int peakConnected() { return this.peakConnected; }
    public int chunks() { return this.chunks; }
    public String client() { return this.client; }
    public List<String> departures() { return List.copyOf(this.departures); }
    /** Whether this roaming bot asked for a new route since last asked; clears the request. */
    public boolean takeIdle(String name) { return this.idle.remove(name); }
    public boolean isAlive() { return this.process.isAlive(); }
    public int exitCode() { return this.process.isAlive() ? -1 : this.process.exitValue(); }

    /**
     * Gives one roaming bot its next route, as "PATH name x,y,z,jump;..." on the process's stdin.
     * Coordinates only ever: the name is one of this stage's own bots.
     */
    public synchronized void sendRoute(String name, List<double[]> route) {
        if (!this.process.isAlive() || route.isEmpty()) return;
        final StringBuilder line = new StringBuilder("PATH ").append(name).append(' ');

        for (int i = 0; i < route.size(); i++) {
            final double[] p = route.get(i);

            if (i > 0) line.append(';');
            line.append(String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%d", p[0], p[1], p[2], (int) p[3]));
        }

        try {
            final OutputStream in = this.process.getOutputStream();
            in.write(line.append('\n').toString().getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException ignored) {
            // The process is ending; the stage stops it anyway.
        }
    }

    /**
     * Asks the bots to leave normally, which takes about a second. Does not wait: the
     * caller is the server thread. A background thread kills the process if it lingers.
     */
    public synchronized void requestStop() {
        if (!this.process.isAlive()) return;

        try {
            final OutputStream in = this.process.getOutputStream();
            in.write("stop\n".getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException ignored) {
            // Already closing; the reaper below handles it.
        }
        final Thread reaper = new Thread(() -> {
            try {
                if (!this.process.waitFor(8, TimeUnit.SECONDS)) {
                    this.logger.warning("Bot process did not stop when asked; killing it.");
                    this.kill();
                }
            } catch (InterruptedException e) {
                this.kill();
            }
        }, "catalyst-bot-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** Immediate. For plugin shutdown, where there is no time to wait. */
    public void kill() {
        this.process.destroyForcibly();
        this.process.descendants().forEach(ProcessHandle::destroyForcibly);
    }

    private static String javaExecutable() {
        // The server's own java: known to exist and to be new enough.
        return ProcessHandle.current().info().command()
                .orElse(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
    }

    private static File ownJar() throws IOException {
        try {
            return new File(BotProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException("cannot locate " + Branding.name() + "'s own jar", e);
        }
    }
}

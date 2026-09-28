// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The container memory limit (Docker, Pterodactyl, Kubernetes). The bot process shares it, so
 * if the two together exceed it the kernel kills the server; the bot launch checks for room
 * first. Reads cgroup v2 then v1; no limit to read (Windows, macOS, bare host) passes the check.
 */
final class ContainerLimits {
    /** Room the bot JVM needs beyond its heap: metaspace, thread stacks, code cache, Netty buffers. */
    static final long JVM_OVERHEAD_BYTES = 160L * 1024 * 1024;
    private static final long SERVER_NATIVE_BYTES = 256L * 1024 * 1024;

    private ContainerLimits() {}

    /**
     * Bytes free for the bot process, or empty when there is no limit. The server is counted at
     * its full -Xmx (heap grows into it as bots load chunks, and on panels -Xmx is most of the
     * limit), or at current use if that is somehow higher.
     */
    static OptionalLong memoryHeadroom() {
        final OptionalLong limit = limit();

        if (limit.isEmpty()) return OptionalLong.empty();
        final long serverAtMost = Runtime.getRuntime().maxMemory()
                + ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getCommitted() + SERVER_NATIVE_BYTES;
        final long reserved = Math.max(usage().orElse(0), serverAtMost);

        return OptionalLong.of(Math.max(0, limit.getAsLong() - reserved));
    }

    /** How full the container is, 0 to 1, or empty when there is no limit. */
    static OptionalDouble fullness() {
        final OptionalLong limit = limit(), usage = usage();

        if (limit.isEmpty() || usage.isEmpty()) return OptionalDouble.empty();

        return OptionalDouble.of((double) usage.getAsLong() / limit.getAsLong());
    }

    /** The server's own heap ceiling, -Xmx, for explaining a refusal. */
    static long serverMaxHeap() {
        return Runtime.getRuntime().maxMemory();
    }

    /**
     * How much of the heap the JVM reserves from boot: -Xms as a fraction of -Xmx. At 1.0 the heap
     * is pinned (-Xms = -Xmx) and holds its full size whether used or not, so nothing hands it back.
     */
    static double heapReservedFraction() {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

        return heap.getMax() > 0 && heap.getInit() > 0 ? (double) heap.getInit() / heap.getMax() : 0;
    }

    static OptionalLong limit() {
        final OptionalLong v2 = read(Path.of("/sys/fs/cgroup/memory.max"));

        return v2.isPresent() ? v2 : read(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
    }

    /**
     * Container memory in use, less the inactive file cache (region files just written) that the
     * kernel reclaims before killing anything - counting it would read near-full on a busy server.
     * Same subtraction docker stats makes.
     */
    private static OptionalLong usage() {
        final String[][] layouts = {
                {"/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory.stat", "inactive_file"},
                {"/sys/fs/cgroup/memory/memory.usage_in_bytes", "/sys/fs/cgroup/memory/memory.stat", "total_inactive_file"}};

        for (final String[] layout : layouts) {
            try {
                final long raw = Long.parseLong(Files.readString(Path.of(layout[0])).trim());
                return OptionalLong.of(Math.max(0, raw - statValue(Path.of(layout[1]), layout[2])));
            } catch (IOException | RuntimeException notHere) {
                // Try the other layout.
            }
        }

        return OptionalLong.empty();
    }

    /** One "key value" line of memory.stat, or 0 when it is missing: never less usage than raw. */
    private static long statValue(Path stat, String key) {
        try {
            return parseStat(Files.readString(stat), key);
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    /** Package-visible for tests. */
    static long parseStat(String stat, String key) {
        for (final String line : stat.split("\n")) {
            final String[] kv = line.trim().split("\\s+");

            if (kv.length == 2 && kv[0].equals(key)) return Long.parseLong(kv[1]);
        }

        return 0;
    }

    private static OptionalLong read(Path limitFile) {
        try {
            return parseLimit(Files.readString(limitFile));
        } catch (IOException | RuntimeException notAContainer) {
            return OptionalLong.empty();
        }
    }

    /**
     * "max" (v2) or a near-2^63 figure (v1's way of saying unlimited) mean no limit.
     * Package-visible for tests.
     */
    static OptionalLong parseLimit(String raw) {
        final String s = raw.trim();

        if (s.isEmpty() || s.equals("max")) return OptionalLong.empty();
        final long value = Long.parseLong(s);

        // v1 reports "unlimited" as the largest page-aligned long.
        if (value >= Long.MAX_VALUE / 2) return OptionalLong.empty();

        return OptionalLong.of(value);
    }

    /** Heap for the bot JVM: enough for Via's mapping data plus a little per bot. */
    static long botHeapBytes(int bots) {
        return (160L + 3L * bots) * 1024 * 1024;
    }

    static long mib(long bytes) {
        return bytes / (1024 * 1024);
    }
}

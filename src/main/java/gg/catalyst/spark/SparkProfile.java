// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A spark profile read from a .sparkprofile file. Field numbers follow spark's spark_sampler.proto;
 * only what's needed to find where time went is decoded, the rest skipped by wire type so later
 * fields don't break the read. Each thread's call tree is flattened: frames in one list, children
 * referenced by index.
 */
public final class SparkProfile {
    public static final class Node {
        public String className = "";
        public String methodName = "";
        public int line;
        public double time;
        public int[] children = new int[0];
    }

    public static final class ThreadData {
        public String name = "";
        public final List<Node> nodes = new ArrayList<>();
        public int[] roots = new int[0];
        public double time;
    }

    public final List<ThreadData> threads = new ArrayList<>();
    /** Class name to the plugin or mod spark attributed it to. */
    public final Map<String, String> classSources = new HashMap<>();
    public long startTime;
    public long endTime;
    public int ticks;

    public long durationMillis() {
        return Math.max(0, this.endTime - this.startTime);
    }

    public static SparkProfile read(File file) throws IOException {
        return parse(Files.readAllBytes(file.toPath()));
    }

    static SparkProfile parse(byte[] bytes) {
        final SparkProfile profile = new SparkProfile();
        final ProtoReader r = new ProtoReader(bytes);

        int tag;

        while ((tag = r.readTag()) != -1) {
            final int field = tag >>> 3, wire = tag & 7;

            switch (field) {
                case 1 -> { if (wire == ProtoReader.LEN) readMetadata(r.readMessage(), profile); else r.skip(wire); }
                case 2 -> { if (wire == ProtoReader.LEN) profile.threads.add(readThread(r.readMessage())); else r.skip(wire); }
                case 3 -> { if (wire == ProtoReader.LEN) readMapEntry(r.readMessage(), profile.classSources); else r.skip(wire); }
                default -> r.skip(wire);
            }
        }

        return profile;
    }

    private static void readMetadata(ProtoReader r, SparkProfile p) {
        int tag;

        while ((tag = r.readTag()) != -1) {
            final int field = tag >>> 3, wire = tag & 7;

            switch (field) {
                case 2 -> { if (wire == ProtoReader.VARINT) p.startTime = r.readVarint(); else r.skip(wire); }
                case 11 -> { if (wire == ProtoReader.VARINT) p.endTime = r.readVarint(); else r.skip(wire); }
                case 12 -> { if (wire == ProtoReader.VARINT) p.ticks = (int) r.readVarint(); else r.skip(wire); }
                default -> r.skip(wire);
            }
        }
    }

    private static ThreadData readThread(ProtoReader r) {
        final ThreadData t = new ThreadData();
        final List<Integer> roots = new ArrayList<>();
        final double[] total = {0};

        int tag;

        while ((tag = r.readTag()) != -1) {
            final int field = tag >>> 3, wire = tag & 7;

            switch (field) {
                case 1 -> { if (wire == ProtoReader.LEN) t.name = r.readString(); else r.skip(wire); }
                case 3 -> { if (wire == ProtoReader.LEN) t.nodes.add(readNode(r.readMessage())); else r.skip(wire); }
                case 4 -> r.readDoubles(wire, v -> total[0] += v);
                case 5 -> r.readInts(wire, roots::add);
                default -> r.skip(wire);
            }
        }
        t.time = total[0];
        t.roots = roots.stream().mapToInt(Integer::intValue).toArray();

        return t;
    }

    private static Node readNode(ProtoReader r) {
        final Node n = new Node();
        final List<Integer> children = new ArrayList<>();
        final double[] total = {0};

        int tag;

        while ((tag = r.readTag()) != -1) {
            final int field = tag >>> 3, wire = tag & 7;

            switch (field) {
                case 3 -> { if (wire == ProtoReader.LEN) n.className = r.readString(); else r.skip(wire); }
                case 4 -> { if (wire == ProtoReader.LEN) n.methodName = r.readString(); else r.skip(wire); }
                case 6 -> { if (wire == ProtoReader.VARINT) n.line = (int) r.readVarint(); else r.skip(wire); }
                case 8 -> r.readDoubles(wire, v -> total[0] += v);
                case 9 -> r.readInts(wire, children::add);
                default -> r.skip(wire);
            }
        }
        n.time = total[0];
        n.children = children.stream().mapToInt(Integer::intValue).toArray();

        return n;
    }

    private static void readMapEntry(ProtoReader r, Map<String, String> into) {
        String key = null, value = null;
        int tag;

        while ((tag = r.readTag()) != -1) {
            final int field = tag >>> 3, wire = tag & 7;

            if (field == 1) key = r.readString();
            else if (field == 2) value = r.readString();
            else r.skip(wire);
        }

        if (key != null && value != null) into.put(key, value);
    }
}

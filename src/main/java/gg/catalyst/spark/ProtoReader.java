// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.spark;

import java.nio.charset.StandardCharsets;

/**
 * Minimal protobuf wire-format reader. spark's generated classes sit under a relocated
 * package that changes between Paper builds; field numbers don't, so this outlives reflection.
 */
final class ProtoReader {
    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LEN = 2;
    static final int FIXED32 = 5;

    private final byte[] buf;
    private int pos;
    private final int end;

    ProtoReader(byte[] buf) { this(buf, 0, buf.length); }

    private ProtoReader(byte[] buf, int start, int end) {
        this.buf = buf;
        this.pos = start;
        this.end = end;
    }

    boolean hasMore() { return this.pos < this.end; }

    /** Returns the next tag, or -1 at the end of the message. */
    int readTag() {
        return this.pos < this.end ? (int) this.readVarint() : -1;
    }

    long readVarint() {
        long result = 0;
        int shift = 0;

        while (true) {
            final byte b = this.buf[this.pos++];
            result |= (long) (b & 0x7F) << shift;

            if ((b & 0x80) == 0) return result;
            shift += 7;

            if (shift > 63) throw new IllegalStateException("malformed varint");
        }
    }

    double readDouble() {
        long bits = 0;

        for (int i = 0; i < 8; i++) bits |= (long) (this.buf[this.pos++] & 0xFF) << (8 * i);

        return Double.longBitsToDouble(bits);
    }

    String readString() {
        final int len = (int) this.readVarint();
        final String s = new String(this.buf, this.pos, len, StandardCharsets.UTF_8);
        this.pos += len;

        return s;
    }

    /** A reader over the next length-delimited field, advancing past it. */
    ProtoReader readMessage() {
        final int len = (int) this.readVarint();
        final ProtoReader sub = new ProtoReader(this.buf, this.pos, this.pos + len);
        this.pos += len;

        return sub;
    }

    void skip(int wireType) {
        switch (wireType) {
            case VARINT -> this.readVarint();
            case FIXED64 -> this.pos += 8;

            // Not "pos += readVarint()": compound assignment reads pos before the call
            // advances it past the length bytes, which silently loses them.
            case LEN -> { final int len = (int) this.readVarint(); this.pos += len; }
            case FIXED32 -> this.pos += 4;
            default -> throw new IllegalStateException("unsupported wire type " + wireType);
        }
    }

    /** Repeated doubles arrive packed (one LEN field) or unpacked (one FIXED64 each). */
    void readDoubles(int wireType, DoubleSink sink) {
        if (wireType == FIXED64) { sink.accept(this.readDouble()); return; }
        final ProtoReader packed = this.readMessage();

        while (packed.hasMore()) sink.accept(packed.readDouble());
    }

    /** Repeated ints arrive packed (one LEN field) or unpacked (one VARINT each). */
    void readInts(int wireType, IntSink sink) {
        if (wireType == VARINT) { sink.accept((int) this.readVarint()); return; }
        final ProtoReader packed = this.readMessage();

        while (packed.hasMore()) sink.accept((int) packed.readVarint());
    }

    interface DoubleSink { void accept(double v); }
    interface IntSink { void accept(int v); }
}

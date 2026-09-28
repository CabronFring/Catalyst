// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.protection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * Drops raw packet frames whose bytes match a known crash exploit before the decoder
 * sees them. Covers bundle-select (negative slot index) and container-click (out-of-range
 * button). Any parse failure passes the packet through untouched.
 */
final class CrashPacketGuard extends ChannelDuplexHandler {

    private final PacketAntiCrashModule module;
    private final PacketIdTable.PacketIds ids;

    CrashPacketGuard(PacketAntiCrashModule module, PacketIdTable.PacketIds ids) {
        this.module = module;
        this.ids = ids;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (this.module.isEnabled() && msg instanceof ByteBuf buf) {
            try {
                if (shouldDrop(buf)) {
                    this.module.increment();
                    buf.release();
                    return;
                }
            } catch (Exception ignored) {}
        }
        super.channelRead(ctx, msg);
    }

    private boolean shouldDrop(ByteBuf buf) {
        if (buf.readableBytes() < 1) return false;

        final int[] id = readVarIntAt(buf, 0);
        if (id == null) return false;

        // Bundle item selected: payload is a single VarInt slot index; crash when < -1.
        if (ids.bundleItemSelected() != PacketIdTable.PacketIds.UNSUPPORTED
                && id[0] == ids.bundleItemSelected()) {
            final int[] slot = readVarIntAt(buf, id[1]);
            return slot != null && slot[0] < -1;
        }

        // Container click: windowId (u8) | stateId (varint) | slot (i16) | button (i8).
        // Crash when button < 0 or > 40.
        if (id[0] == ids.containerClick()) {
            int offset = id[1] + 1; // window-id is a single unsigned byte
            final int[] state = readVarIntAt(buf, offset);
            if (state == null) return false;
            offset += state[1] + 2; // skip state-id and slot (short)
            if (buf.readerIndex() + offset >= buf.writerIndex()) return false;
            final byte button = buf.getByte(buf.readerIndex() + offset);
            return button < 0 || button > 40;
        }

        return false;
    }

    /**
     * Non-destructive VarInt read at {@code readerIndex + offset}.
     * Returns {@code [value, bytesConsumed]} or {@code null} on underflow or malformed input.
     */
    static int[] readVarIntAt(ByteBuf buf, int offset) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            final int idx = buf.readerIndex() + offset + i;
            if (idx >= buf.writerIndex()) return null;
            final byte b = buf.getByte(idx);
            value |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) return new int[]{value, i + 1};
        }
        return null;
    }
}

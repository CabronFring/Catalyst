// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * A stopwatch mark inserted between two existing handlers: it stamps the clock and passes the
 * packet straight on. Every measurement is wrapped so a fault here can never stop a packet - the
 * worst case is a missing timing, not a dropped connection. Probes are only added and removed, so
 * removing them restores the pipeline as it was.
 */
public final class ProbeHandler extends ChannelDuplexHandler {
    private final ProfileSession session;
    private final String attributeTo;

    /**
     * @param attributeTo the handler immediately before this probe, whose processing
     *                    time this probe closes off. Null for the probe at the head.
     */
    public ProbeHandler(ProfileSession session, String attributeTo) {
        this.session = session;
        this.attributeTo = attributeTo;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            this.session.mark(this.attributeTo);
        } catch (Throwable ignored) {
            // Never let measurement interfere with delivery.
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public boolean isSharable() {
        // Each probe is bound to one position in one pipeline.
        return false;
    }
}

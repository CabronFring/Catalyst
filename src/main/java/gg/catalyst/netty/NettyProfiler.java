// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Times each handler in one player's packet pipeline for a window, by inserting stopwatch probes
 * between the existing handlers - none is replaced or wrapped, and removing the probes leaves the
 * pipeline as it was found. Measures inbound packets, where anticheats and the like do their
 * expensive work and where a slow handler stalls the event loop for the whole connection.
 */
public final class NettyProfiler {
    private static final String PROBE_PREFIX = "catalyst-probe-";

    private final Channel channel;
    private final ProfileSession session;
    private final List<String> probeNames = new ArrayList<>();
    private volatile boolean stopped = false;

    private NettyProfiler(Channel channel, ProfileSession session) {
        this.channel = channel;
        this.session = session;
    }

    public ProfileSession session() { return this.session; }

    public static NettyProfiler start(Player player) throws NettyInspector.Unavailable {
        final Object found = NettyInspector.channelFor(player);

        if (!(found instanceof Channel channel))
            throw new NettyInspector.Unavailable("Found a connection object that is not a Netty channel.");

        if (!channel.isActive())
            throw new NettyInspector.Unavailable("That connection is no longer active.");

        final ProfileSession session = new ProfileSession();
        final NettyProfiler profiler = new NettyProfiler(channel, session);
        profiler.install();

        return profiler;
    }

    private void install() throws NettyInspector.Unavailable {
        final ChannelPipeline pipeline = this.channel.pipeline();

        // Snapshot first: inserting probes mutates the pipeline we would be iterating.
        final List<String> handlers = new ArrayList<>(pipeline.names());

        try {
            String previous = null;
            int index = 0;

            for (final String name : handlers) {
                if (name == null || name.startsWith(PROBE_PREFIX)) continue;

                if (pipeline.get(name) == null) continue;

                this.session.registerOwner(name, NettyInspector.owningPluginOf(pipeline.get(name)));

                final String probeName = PROBE_PREFIX + (index++);
                pipeline.addBefore(name, probeName, new ProbeHandler(this.session, previous));
                this.probeNames.add(probeName);
                previous = name;
            }

            // Closes off the final handler, which otherwise has no probe after it.
            if (previous != null) {
                final String tailProbe = PROBE_PREFIX + index;
                pipeline.addLast(tailProbe, new ProbeHandler(this.session, previous));
                this.probeNames.add(tailProbe);
            }

        } catch (Throwable t) {
            // Never leave a half-instrumented pipeline behind.
            this.stop();
            throw new NettyInspector.Unavailable("Could not attach probes (" + t.getClass().getSimpleName() + ").");
        }

        if (this.probeNames.isEmpty())
            throw new NettyInspector.Unavailable("No handlers were available to measure.");
    }

    /** Removes every probe. Safe to call repeatedly, and safe after a disconnect. */
    public void stop() {
        if (this.stopped) return;
        this.stopped = true;

        ChannelPipeline pipeline;

        try {
            pipeline = this.channel.pipeline();
        } catch (Throwable gone) {
            return;
        }

        for (final String name : this.probeNames) {
            try {
                if (pipeline.get(name) != null) pipeline.remove(name);
            } catch (Throwable ignored) {
                // A closed channel discards its pipeline anyway.
            }
        }
        this.probeNames.clear();
    }

    public boolean isStopped() { return this.stopped; }
}

// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Reports what is in a player's Netty pipeline and which plugin put it there - a slow packet
 * handler blocks the event loop for that connection and the Bukkit API won't tell you it exists.
 * Strictly read-only: the pipeline is never touched, so a failure here reports badly rather than
 * dropping anyone. Netty isn't on the API classpath, so it finds the channel by type (which
 * survives remapping) through reflection rather than by obfuscated field names.
 */
public final class NettyInspector {
    private static final String CHANNEL_INTERFACE = "io.netty.channel.Channel";
    private static final int MAX_DEPTH = 4;

    // A ServerPlayer reaches most of the world through its fields; a budget keeps an unbounded
    // reflective walk off the server thread. The real channel is only a few hops away.
    private static final int MAX_OBJECTS = 2_000;

    private NettyInspector() {}

    /** Thrown with a readable reason when the pipeline cannot be reached. */
    public static final class Unavailable extends Exception {
        Unavailable(String message) { super(message); }
    }

    public static List<HandlerInfo> inspect(Player player) throws Unavailable {
        return readPipeline(channelFor(player));
    }

    /** Locates the Netty channel behind a player's connection. */
    public static Object channelFor(Player player) throws Unavailable {
        final Object handle = serverPlayer(player);
        final Object channel = findChannel(handle, 0, Collections.newSetFromMap(new IdentityHashMap<>()), new int[1]);

        if (channel == null)
            throw new Unavailable("Could not locate the Netty channel on this server implementation.");

        return channel;
    }

    /** Which plugin supplied this handler instance, or null when the server owns it. */
    public static String owningPluginOf(Object handler) {
        return handler == null ? null : owningPlugin(handler.getClass());
    }

    private static Object serverPlayer(Player player) throws Unavailable {
        try {
            final Method getHandle = player.getClass().getMethod("getHandle");
            getHandle.setAccessible(true);
            final Object handle = getHandle.invoke(player);

            if (handle == null) throw new Unavailable("The server returned no connection handle for that player.");
            return handle;
        } catch (Unavailable u) {
            throw u;
        } catch (Throwable t) {
            throw new Unavailable("This server does not expose player internals (" + t.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Walks object fields breadth-limited until something implementing Netty's Channel
     * turns up. Depth is capped and visited objects tracked, so a cyclic object graph
     * cannot spin here.
     */
    private static Object findChannel(Object root, int depth, Set<Object> visited, int[] budget) {
        if (root == null || depth > MAX_DEPTH || budget[0]++ > MAX_OBJECTS) return null;

        if (!visited.add(root)) return null;

        if (isChannel(root.getClass())) return root;

        final List<Object> deferred = new ArrayList<>();

        for (Class<?> type = root.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (final Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || field.getType().isArray()) continue;

                final String typeName = field.getType().getName();

                // Collections and JDK types never hold the channel and are expensive to walk.
                if (typeName.startsWith("java.")) continue;

                Object value;

                try {
                    field.setAccessible(true);
                    value = field.get(root);
                } catch (Throwable inaccessible) {
                    continue;
                }

                if (value == null) continue;

                if (isChannel(value.getClass())) return value;

                // The real route is player -> connection -> connection -> channel, so
                // anything network-shaped is tried before the rest of the object graph.
                if (looksNetworkRelated(field.getName(), typeName)) {
                    final Object found = findChannel(value, depth + 1, visited, budget);

                    if (found != null) return found;
                } else {
                    deferred.add(value);
                }
            }
        }

        for (Object value : deferred) {
            final Object found = findChannel(value, depth + 1, visited, budget);

            if (found != null) return found;
        }

        return null;
    }

    private static boolean looksNetworkRelated(String fieldName, String typeName) {
        final String field = fieldName.toLowerCase();
        final String type = typeName.toLowerCase();

        return field.contains("connection") || field.contains("network") || field.contains("channel")
            || type.contains("connection") || type.contains("network") || type.contains("netty");
    }

    private static boolean isChannel(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (CHANNEL_INTERFACE.equals(c.getName())) return true;

            for (final Class<?> iface : c.getInterfaces())
                if (CHANNEL_INTERFACE.equals(iface.getName()) || isChannel(iface)) return true;
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<HandlerInfo> readPipeline(Object channel) throws Unavailable {
        try {
            final Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);

            if (pipeline == null) throw new Unavailable("The channel reported no pipeline.");

            final Method toMap = pipeline.getClass().getMethod("toMap");
            toMap.setAccessible(true);
            final Map<String, Object> handlers = (Map<String, Object>) toMap.invoke(pipeline);

            final List<HandlerInfo> out = new ArrayList<>();

            for (final Map.Entry<String, Object> entry : handlers.entrySet()) {
                final Object handler = entry.getValue();

                if (handler == null) continue;
                final Class<?> handlerClass = handler.getClass();
                out.add(new HandlerInfo(entry.getKey(), handlerClass.getName(), owningPlugin(handlerClass)));
            }
            return out;

        } catch (Unavailable u) {
            throw u;
        } catch (Throwable t) {
            throw new Unavailable("Could not read the pipeline (" + t.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Bukkit loads each plugin in its own classloader, so the class of a handler is
     * enough to say who installed it. Returns null for handlers the server itself owns.
     */
    private static String owningPlugin(Class<?> handlerClass) {
        try {
            final JavaPlugin plugin = JavaPlugin.getProvidingPlugin(handlerClass);
            return plugin == null ? null : plugin.getName();
        } catch (Throwable notAPlugin) {
            return null;
        }
    }
}

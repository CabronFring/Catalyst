// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.netty;

/**
 * One handler in a connection's Netty pipeline.
 *
 * @param name       the pipeline's own name for the handler
 * @param className  fully qualified class, useful when the name is generic
 * @param pluginName owning plugin, or null when the server itself installed it
 */
public record HandlerInfo(String name, String className, String pluginName) {
    public boolean fromPlugin() {
        return pluginName != null;
    }

    public String simpleClassName() {
        final int dot = className.lastIndexOf('.');

        return dot < 0 ? className : className.substring(dot + 1);
    }
}

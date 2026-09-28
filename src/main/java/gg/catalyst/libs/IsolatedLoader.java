// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.libs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * A classloader over downloaded jars that cannot see the server's classes. Its parent is
 * the JDK's platform loader, not the plugin's, so a downloaded Netty or Adventure never
 * meets the server's copy - each side only ever resolves its own.
 */
public final class IsolatedLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private IsolatedLoader(URL[] urls) {
        super("catalyst-isolated", urls, ClassLoader.getPlatformClassLoader());
    }

    /** Only pass files that came from {@link LibraryDownloader#verifiedFiles()}. */
    public static IsolatedLoader of(List<File> verifiedJars) throws MalformedURLException {
        final URL[] urls = new URL[verifiedJars.size()];

        for (int i = 0; i < urls.length; i++) urls[i] = verifiedJars.get(i).toURI().toURL();

        return new IsolatedLoader(urls);
    }

    /**
     * Loads a class without running its static initialisers, proving the jar set is intact
     * and readable without starting anything (MCProtocolLib's statics spin up Netty).
     */
    public void check(String className) throws IOException {
        try {
            Class.forName(className, false, this);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IOException("downloaded libraries are incomplete: " + e, e);
        }
    }
}

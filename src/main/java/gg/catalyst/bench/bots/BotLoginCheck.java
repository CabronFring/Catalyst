// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import java.net.InetAddress;

/** BotStage#verifyLogin, as the login listeners see it. */
@FunctionalInterface
interface BotLoginCheck {
    /** True, and remembered, when this login is one of the stage's own bots. */
    boolean verify(String name, InetAddress address, String handshakeHost);
}

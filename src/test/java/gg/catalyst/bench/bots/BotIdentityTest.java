// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.bench.bots;

import org.junit.jupiter.api.Test;

import static gg.catalyst.bench.bots.BotStage.hostMatches;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotIdentityTest {
    private static final String TOKEN_HOST = "catalyst-0123456789abcdef0123456789abcdef.invalid";

    @Test
    void acceptsEveryFormServersReport() {
        assertTrue(hostMatches(TOKEN_HOST, TOKEN_HOST)); // Paper virtual host
        assertTrue(hostMatches(TOKEN_HOST + ":25599", TOKEN_HOST)); // PlayerLoginEvent
        assertTrue(hostMatches(TOKEN_HOST.toUpperCase(), TOKEN_HOST)); // case is not significant in hosts
        assertTrue(hostMatches(TOKEN_HOST + "\u0000127.0.0.1\u0000" + "56c4165f519930c0a70ec35bbbec8d92",
                TOKEN_HOST)); // raw BungeeCord handshake
    }

    @Test
    void refusesAnythingElse() {
        assertFalse(hostMatches("127.0.0.1", TOKEN_HOST));
        assertFalse(hostMatches("localhost:25599", TOKEN_HOST));
        assertFalse(hostMatches("", TOKEN_HOST));

        // Another stage's token, or a guess of the right shape.
        assertFalse(hostMatches("catalyst-ffffffffffffffffffffffffffffffff.invalid", TOKEN_HOST));

        // The token as a prefix or suffix of something longer does not count.
        assertFalse(hostMatches(TOKEN_HOST + ".evil.example", TOKEN_HOST));
        assertFalse(hostMatches("x" + TOKEN_HOST, TOKEN_HOST));

        // Token hidden in forwarded data rather than in the host itself.
        assertFalse(hostMatches("play.example\u0000127.0.0.1\u0000" + TOKEN_HOST, TOKEN_HOST));
    }
}

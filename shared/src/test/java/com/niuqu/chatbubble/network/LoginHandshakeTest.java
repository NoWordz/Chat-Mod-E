package com.niuqu.chatbubble.network;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The login announce is the one place the three client hooks share, and it is the
 * exact step the M5 regression dropped on Forge/NeoForge: without the v2 offer the
 * server never adds the player to {@code groupV2Clients}, so it never pushes the
 * group directory and 「创建」 looks dead. These pin both branches, on real recorded
 * sends rather than a source-text or no-exception check.
 */
class LoginHandshakeTest {

    /** A named record of a send, so the assertions can name which hello fired and in what order. */
    private record Sends(List<String> order) {
        Runnable v1() { return () -> order.add("v1"); }
        Runnable v2() { return () -> order.add("v2"); }
    }

    @Test
    void peerWithoutGroupsChannelGetsOnlyTheV1Hello() {
        List<String> order = new ArrayList<>();
        Sends sends = new Sends(order);

        LoginHandshake.announce(false, sends.v1(), sends.v2());

        assertEquals(List.of("v1"), order,
            "an old/new server that never declared the groups channel keeps the 2.4.x v1-only flow");
        assertFalse(order.contains("v2"));
    }

    @Test
    void peerWithGroupsChannelGetsBothHellos() {
        List<String> order = new ArrayList<>();
        Sends sends = new Sends(order);

        LoginHandshake.announce(true, sends.v1(), sends.v2());

        assertEquals(List.of("v1", "v2"), order,
            "the v2 offer must reach a server that declared the groups channel, or the directory never arrives");
    }
}

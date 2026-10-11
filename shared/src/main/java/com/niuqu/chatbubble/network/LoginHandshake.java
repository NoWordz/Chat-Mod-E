package com.niuqu.chatbubble.network;

/**
 * The loader-neutral login announce — one implementation for Forge, NeoForge and
 * Fabric, so the three client hooks cannot drift apart.
 *
 * <p>Every login must send the v1 hello (it routes the 2.4.x group chat), and,
 * when the peer declared the optional {@code e33chat:groups} channel, the v2
 * group-protocol offer on top of it. The v2 offer is what makes the server add
 * this connection to its {@code groupV2Clients} set and push the
 * recipient-scoped directory the group browser is built on: without it the
 * server's {@code sendDirectory} returns early, the client never learns about any
 * group, and clicking 「创建」 looks like a no-op even though the action itself
 * still reaches the server.
 *
 * <p>The old-server path is preserved by construction: a peer that never declared
 * the groups channel gets the v1 hello only, exactly the 2.4.x behaviour.
 */
public final class LoginHandshake {

    private LoginHandshake() {}

    /**
     * Runs the v1 hello always, and the v2 offer only when the peer declared the
     * groups channel. Ordered v1-then-v2 so the server's cheap v1 hook runs before
     * the directory push.
     */
    public static void announce(boolean groupsChannelPresent, Runnable helloV1, Runnable helloV2) {
        helloV1.run();
        if (groupsChannelPresent) helloV2.run();
    }
}

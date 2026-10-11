package com.niuqu.chatbubble.server;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side half of the group-chat handshake: the {@code e33chat:groups}
 * HELLO is what admits a connection to {@link GroupManager#groupV2Clients}, and
 * that set is the exact gate {@code sendDirectory} checks before pushing the
 * group directory. M5's client regression skipped the client's HELLO, which left
 * this set empty and dropped the directory — so this pins the accept/refuse
 * transition on the real set (not a copy, not a source-text check).
 */
class GroupProtocolHandshakeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @AfterEach
    void dropRegisteredClient() {
        GroupManager.groupV2Clients.remove(PLAYER);
    }

    @Test
    void acceptedHelloRegistersTheConnectionForDirectoryDelivery() {
        assertFalse(GroupManager.groupV2Clients.contains(PLAYER),
            "a connection starts outside the directory gate");

        boolean accepted = GroupManager.registerV2Client(PLAYER, GroupManager.PROTOCOL_VERSION);

        assertTrue(accepted);
        assertTrue(GroupManager.groupV2Clients.contains(PLAYER),
            "after the handshake the directory gate must let this player through, or the group browser stays empty");
    }

    @Test
    void refusedDialectIsNotRegistered() {
        boolean accepted = GroupManager.registerV2Client(PLAYER, GroupManager.PROTOCOL_VERSION + 99);

        assertFalse(accepted, "only the server's own protocol version is accepted");
        assertFalse(GroupManager.groupV2Clients.contains(PLAYER),
            "a refused dialect gets no directory");
    }

    @Test
    void reRefusedDialectForgetsAnEarlierAcceptance() {
        GroupManager.registerV2Client(PLAYER, GroupManager.PROTOCOL_VERSION);
        assertTrue(GroupManager.groupV2Clients.contains(PLAYER));

        GroupManager.registerV2Client(PLAYER, GroupManager.PROTOCOL_VERSION + 1);

        assertFalse(GroupManager.groupV2Clients.contains(PLAYER),
            "a later refusal must revoke the directory this connection was granted");
    }
}

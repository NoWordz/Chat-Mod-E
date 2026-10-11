package com.niuqu.chatbubble;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Forge login announce is the exact step M5 dropped: the v1 hello went out,
 * the v2 group-protocol offer did not, so the server never added the player to
 * {@code groupV2Clients}, never pushed the directory, and 「创建」 looked dead.
 *
 * <p>This drives the real announce (the body the {@code onLoggingIn} hook calls)
 * with recorded senders, so it pins the platform behaviour itself rather than a
 * source-text or no-exception check.
 */
class ChatBubbleClientLoginHandshakeTest {

    private final List<String> sends = new ArrayList<>();

    @AfterEach
    void restoreSenders() {
        ChatBubbleClientListener.helloV1Sender =
            com.niuqu.chatbubble.packets.ClientHelloPacket::send;
        ChatBubbleClientListener.helloV2Sender =
            com.niuqu.chatbubble.packets.GroupProtocolHelloPacket::send;
    }

    @Test
    void loginAnnounceSendsV1ThenV2() {
        ChatBubbleClientListener.helloV1Sender = () -> sends.add("v1");
        ChatBubbleClientListener.helloV2Sender = () -> sends.add("v2");

        ChatBubbleClientListener.announceToServer();

        assertEquals(List.of("v1", "v2"), sends,
            "the Forge login announce must offer the v2 handshake too, or the server never pushes the group directory");
    }
}

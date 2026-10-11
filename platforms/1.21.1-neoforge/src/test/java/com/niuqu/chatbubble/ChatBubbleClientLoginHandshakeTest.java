package com.niuqu.chatbubble;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The NeoForge login announce must offer the v2 group handshake whenever the peer
 * declared that payload id. M5 dropped exactly this, so the server never learned
 * the player speaks v2, never pushed the group directory, and 「创建」 looked dead.
 *
 * <p>Drives the real announce (the body {@code onLoggingIn} calls) with recorded
 * senders and both gating values, on behaviour rather than source text.
 */
class ChatBubbleClientLoginHandshakeTest {

    private final List<String> sends = new ArrayList<>();

    @AfterEach
    void restoreSenders() {
        ChatBubbleClientListener.helloV1Sender =
            com.niuqu.chatbubble.packets.ClientHelloPayload::send;
        ChatBubbleClientListener.helloV2Sender =
            com.niuqu.chatbubble.packets.GroupProtocolHelloPayload::send;
    }

    @Test
    void announceOffersV2WhenGroupsChannelPresent() {
        ChatBubbleClientListener.helloV1Sender = () -> sends.add("v1");
        ChatBubbleClientListener.helloV2Sender = () -> sends.add("v2");

        ChatBubbleClientListener.announce(true);

        assertEquals(List.of("v1", "v2"), sends,
            "a server that declared the groups channel must receive the v2 offer, or the group browser never fills");
    }

    @Test
    void announceSkipsV2WhenGroupsChannelAbsent() {
        ChatBubbleClientListener.helloV1Sender = () -> sends.add("v1");
        ChatBubbleClientListener.helloV2Sender = () -> sends.add("v2");

        ChatBubbleClientListener.announce(false);

        assertEquals(List.of("v1"), sends,
            "an old/vanilla server never declared the payload id; sending v2 there would hard-throw in the login flow");
    }
}

package com.niuqu.chatbubble;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Fabric join announce is the reference behaviour the other two loaders must
 * match: it offers the v2 group handshake — but only when the server declared the
 * channel. Drives the real announce (the body the JOIN hook calls) with recorded
 * senders, on behaviour rather than source text.
 */
class ChatBubbleClientLoginHandshakeTest {

    private final List<String> sends = new ArrayList<>();

    @AfterEach
    void restoreSenders() {
        ChatBubbleClientSetup.helloV1Sender =
            com.niuqu.chatbubble.network.ClientHelloPayload::send;
        ChatBubbleClientSetup.helloV2Sender =
            com.niuqu.chatbubble.network.GroupProtocolHelloPayload::send;
    }

    @Test
    void announceOffersV2WhenGroupsChannelPresent() {
        ChatBubbleClientSetup.helloV1Sender = () -> sends.add("v1");
        ChatBubbleClientSetup.helloV2Sender = () -> sends.add("v2");

        ChatBubbleClientSetup.announceOnJoin(true);

        assertEquals(List.of("v1", "v2"), sends,
            "a server that declared the groups channel must receive the v2 offer, or the group browser never fills");
    }

    @Test
    void announceSkipsV2WhenGroupsChannelAbsent() {
        ChatBubbleClientSetup.helloV1Sender = () -> sends.add("v1");
        ChatBubbleClientSetup.helloV2Sender = () -> sends.add("v2");

        ChatBubbleClientSetup.announceOnJoin(false);

        assertEquals(List.of("v1"), sends,
            "a 2.4.x server never declared the channel; the v1-only flow stays intact");
    }
}

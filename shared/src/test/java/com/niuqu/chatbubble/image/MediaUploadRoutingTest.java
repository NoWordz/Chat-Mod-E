package com.niuqu.chatbubble.image;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.5.0 M9 client wiring / master-plan §13 D6.
 *
 * <p>An image composed while a group channel is active must be hosted on the
 * group channel — the server then stores it under a private {@code g_} id bound
 * to that group UUID — while an image composed anywhere else keeps the ordinary
 * public upload. The two paths must never be confused, and a group upload must
 * never fall back to the public third-party host: that would publish a private
 * group image as a public link (the exact D6 gap M9 left unconnected).
 *
 * <p>Driven through {@link MediaUploadRouting#resolve} with a recording sink, so
 * the decision is pinned without a Minecraft client or a live network.
 */
class MediaUploadRoutingTest {

    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    /** Records which host channel a resolve call picked, and what it returned. */
    private static final class RecordingSink implements MediaUploadRouting.Host {
        UUID groupGivenToHost;
        int publicCalls;
        int groupCalls;
        int thirdPartyCalls;
        String publicResult = "e33chat://media/0123456789abcdef0123456789abcdef";
        String groupResult = "e33chat://media/g_0123456789abcdef0123456789abcdef";
        String thirdPartyResult = "https://third.example/x.png";
        boolean groupHostFails;
        boolean groupHostThrows;

        @Override public String uploadPublic(byte[] bytes, String contentType) {
            publicCalls++;
            return publicResult;
        }

        @Override public String uploadGroup(UUID groupId, byte[] bytes, String contentType) {
            groupCalls++;
            groupGivenToHost = groupId;
            if (groupHostThrows) throw new IllegalStateException("group host blew up");
            return groupHostFails ? null : groupResult;
        }

        @Override public String uploadThirdParty(byte[] bytes, String fileName) {
            thirdPartyCalls++;
            return thirdPartyResult;
        }
    }

    private static byte[] bytes() {
        return new byte[] {1, 2, 3, 4};
    }

    @Test
    void groupUploadGoesToTheGroupChannelAndYieldsAPrivateId() {
        RecordingSink sink = new RecordingSink();
        String url = MediaUploadRouting.resolve(GROUP, bytes(), "a.png", "image/png", sink);
        assertEquals(sink.groupResult, url, "the group host's url is the one returned");
        assertEquals(GROUP, sink.groupGivenToHost, "the composed group must reach the group host");
        assertEquals(1, sink.groupCalls);
        assertEquals(0, sink.publicCalls, "a group image must never be uploaded as a public file");
        assertEquals(0, sink.thirdPartyCalls);
        assertTrue(url.contains("/g_"), "the group host returns the private g_ id");
    }

    @Test
    void publicUploadStaysOnThePublicChannel() {
        RecordingSink sink = new RecordingSink();
        String url = MediaUploadRouting.resolve(null, bytes(), "a.png", "image/png", sink);
        assertEquals(sink.publicResult, url);
        assertEquals(1, sink.publicCalls);
        assertEquals(0, sink.groupCalls, "a public image must never be routed through a group");
        assertEquals(0, sink.thirdPartyCalls);
        assertFalse(url.contains("/g_"), "the public host never returns a private id");
    }

    @Test
    void groupUploadNeverFallsBackToThePublicThirdPartyHost() {
        RecordingSink sink = new RecordingSink();
        sink.groupHostFails = true;
        String url = MediaUploadRouting.resolve(GROUP, bytes(), "a.png", "image/png", sink);
        assertNull(url, "a group image the group host refuses must fail, not be published");
        assertEquals(0, sink.publicCalls);
        assertEquals(0, sink.thirdPartyCalls, "the public fallback must never see a group image");
    }

    @Test
    void aThrowingGroupHostStillDoesNotFallBackToPublic() {
        RecordingSink sink = new RecordingSink();
        sink.groupHostThrows = true;
        assertNull(MediaUploadRouting.resolve(GROUP, bytes(), "a.png", "image/png", sink));
        assertEquals(0, sink.publicCalls);
        assertEquals(0, sink.thirdPartyCalls);
    }

    @Test
    void publicUploadFallsBackToThirdPartyWhenServerHostingIsUnavailable() {
        RecordingSink sink = new RecordingSink();
        sink.publicResult = null;
        String url = MediaUploadRouting.resolve(null, bytes(), "a.png", "image/png", sink);
        assertEquals(sink.thirdPartyResult, url);
        assertEquals(1, sink.thirdPartyCalls);
        assertEquals(0, sink.groupCalls);
    }

    @Test
    void emptyBytesResolveToNothingOnEitherPath() {
        RecordingSink sink = new RecordingSink();
        assertNull(MediaUploadRouting.resolve(GROUP, new byte[0], "a.png", "image/png", sink));
        assertNull(MediaUploadRouting.resolve(null, new byte[0], "a.png", "image/png", sink));
        assertEquals(0, sink.groupCalls);
        assertEquals(0, sink.publicCalls);
    }
}

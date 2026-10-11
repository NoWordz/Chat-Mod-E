package com.niuqu.chatbubble.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8: the inline image span carries a preview marker in its click value, so the
 * chat screen can intercept the click and open the full-screen viewer instead of
 * the system browser (the browser stays reachable from inside the viewer).
 */
class ImagePreviewLinkTest {

    @Test
    void encodeDecodeRoundTrips() {
        String url = "https://example.com/a.png?x=1&y=2";
        String marked = ImagePreviewLink.encode(url);
        assertTrue(marked.startsWith(ImagePreviewLink.PREFIX));
        assertEquals(url, ImagePreviewLink.decode(marked));
    }

    @Test
    void plainUrlIsNotAPreviewMarker() {
        assertNull(ImagePreviewLink.decode("https://example.com/a.png"));
        assertNull(ImagePreviewLink.decode("e33chat://media/abc"));
        assertNull(ImagePreviewLink.decode(null));
        assertNull(ImagePreviewLink.decode(""));
    }

    @Test
    void markerIsRecognisableBeforeDecoding() {
        assertTrue(ImagePreviewLink.isPreviewClick(ImagePreviewLink.encode("http://x/y.gif")));
        assertFalse(ImagePreviewLink.isPreviewClick("http://x/y.gif"));
        assertFalse(ImagePreviewLink.isPreviewClick(null));
    }

    @Test
    void emptyUrlMarkerStillRoundTripsToEmptyNotGarbage() {
        String marked = ImagePreviewLink.encode("");
        assertTrue(ImagePreviewLink.isPreviewClick(marked));
        assertEquals("", ImagePreviewLink.decode(marked));
    }
}

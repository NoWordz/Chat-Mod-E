package com.niuqu.chatbubble.logic;

/**
 * M8 (§13 D3) — the marker carried by an inline image's click value.
 *
 * <p>The inline thumbnail keeps its click event so the chat shell can react, but
 * the primary action is now the in-game full-screen preview, not the system
 * browser. The image span therefore encodes its URL behind
 * {@link #PREFIX} in the click value; the screen intercepts a marked click and
 * opens the viewer. Everything else (ordinary {@code http(s)} links, names,
 * commands) passes through untouched, and the browser stays reachable from a
 * button inside the viewer.
 *
 * <p>Kept platform-free on purpose: all three render shells share this exact
 * encoding, so a click behaves identically on Forge, NeoForge and Fabric.
 */
public final class ImagePreviewLink {
    private ImagePreviewLink() {}

    /** Click-value prefix that means "open the preview for the rest of this value". */
    public static final String PREFIX = "e33chat-preview:";

    /** Wraps an image URL into a preview click value. */
    public static String encode(String url) {
        return PREFIX + (url == null ? "" : url);
    }

    /** The image URL carried by a preview click value, or {@code null} when the value is not a preview marker. */
    public static String decode(String clickValue) {
        if (clickValue == null || !clickValue.startsWith(PREFIX)) return null;
        return clickValue.substring(PREFIX.length());
    }

    /** True when a click value was produced by {@link #encode}. */
    public static boolean isPreviewClick(String clickValue) {
        return decode(clickValue) != null;
    }
}

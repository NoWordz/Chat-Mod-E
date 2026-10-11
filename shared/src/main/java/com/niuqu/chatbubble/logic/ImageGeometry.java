package com.niuqu.chatbubble.logic;

/**
 * M8 (§13 D3) — platform-free image geometry shared by all three targets.
 *
 * <p>Two jobs, both pure maths so the three render shells cannot drift:
 *
 * <ul>
 *   <li><b>Inline thumbnail</b>: an embedded image is always a small thumbnail.
 *       Its long edge converges on {@link #THUMBNAIL_MAX_LONG_EDGE} but never
 *       grows past the panel's usable width, and a small image is never
 *       upscaled. This replaces the old "long edge = panel width − avatar −
 *       margins" rule that made every large image flood the bubble, and it is
 *       applied to the not-yet-loaded placeholder too, so the loading state no
 *       longer paints a panel-wide empty square that jumps when the image
 *       finally decodes.</li>
 *
 *   <li><b>Full-screen preview</b>: a 1:1 {@link #previewRect} layout — the
 *       image is aspect-fitted inside the view (upscaling a small image so it can
 *       be inspected), multiplied by the zoom, and panned, with pan locked to the
 *       margin whenever the image overflows that axis and disabled when it fits
 *       (so a fitted image stays centred and can never be dragged off-screen).</li>
 * </ul>
 *
 * <p>No {@code net.minecraft} imports: the guard only scans this package, and
 * this class is the part of M8 that must be written once, not three times.
 */
public final class ImageGeometry {
    private ImageGeometry() {}

    /**
     * Longest edge of an inline image thumbnail, in GUI pixels. 180 is chosen
     * deliberately: it is close to the ~120-200px inline-image width of mature
     * chat clients (QQ / WeChat / Discord), roughly half the pixel budget of the
     * narrowest usable bubble, and large enough that a 16:9 image still reads at
     * 180x101 — while a full-size photo only occupies the corner of the bubble
     * instead of the entire panel.
     */
    public static final int THUMBNAIL_MAX_LONG_EDGE = 180;

    /** Preview zoom bounds. 1 = fit; the lower bound keeps a huge image usable, the upper allows pixel peeping. */
    public static final float MIN_ZOOM = 0.25f;
    public static final float MAX_ZOOM = 8f;

    /** Integer screen rectangle. */
    public record Rect(int x, int y, int w, int h) {}

    /**
     * Aspect-preserving inline thumbnail size.
     *
     * @param imgW,imgH     source pixels (non-positive → 1x1)
     * @param maxLongEdge   cap for the longer edge (the {@link #THUMBNAIL_MAX_LONG_EDGE} token)
     * @param maxWidth      usable bubble width; the result never exceeds it
     */
    public static int[] thumbnailSize(int imgW, int imgH, int maxLongEdge, int maxWidth) {
        if (imgW <= 0 || imgH <= 0) return new int[]{1, 1};
        int longEdge = Math.max(imgW, imgH);
        double scale = Math.min(1.0, (double) maxLongEdge / longEdge);
        int w = Math.max(1, (int) Math.round(imgW * scale));
        int h = Math.max(1, (int) Math.round(imgH * scale));
        if (maxWidth > 0 && w > maxWidth) {
            double shrink = (double) maxWidth / w;
            w = Math.max(1, Math.min(w, maxWidth));
            h = Math.max(1, (int) Math.round(h * shrink));
        }
        return new int[]{w, h};
    }

    /**
     * Placeholder box while the image is still loading (aspect unknown). A small
     * square, so the layout does not jump when the real thumbnail arrives.
     */
    public static int[] thumbnailPlaceholder(int maxLongEdge, int maxWidth) {
        if (maxLongEdge <= 0 || maxWidth <= 0) return new int[]{1, 1};
        int edge = Math.max(1, Math.min(maxLongEdge, maxWidth));
        return new int[]{edge, edge};
    }

    /** Clamps a zoom factor into [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}]; NaN falls back to 1 (fit). */
    public static float clampZoom(float zoom) {
        if (Float.isNaN(zoom)) return 1f;
        if (zoom < MIN_ZOOM) return MIN_ZOOM;
        if (zoom > MAX_ZOOM) return MAX_ZOOM;
        return zoom;
    }

    /**
     * Aspect-fit + zoom + pan rectangle for the full-screen preview, in view
     * coordinates. {@code margin} is the minimum gap kept from the view edges.
     */
    public static Rect previewRect(int imgW, int imgH, int viewW, int viewH,
                                   int margin, float zoom, int panX, int panY) {
        int availW = Math.max(1, viewW - 2 * margin);
        int availH = Math.max(1, viewH - 2 * margin);
        int iw = Math.max(1, imgW);
        int ih = Math.max(1, imgH);
        float scale = Math.min((float) availW / iw, (float) availH / ih) * clampZoom(zoom);
        int w = Math.max(1, Math.round(iw * scale));
        int h = Math.max(1, Math.round(ih * scale));

        int x;
        if (w > availW) {
            // Overflows this axis: allow panning, but clamp so no edge leaves a gap.
            x = clamp((viewW - w) / 2 + panX, viewW - margin - w, margin);
        } else {
            x = (viewW - w) / 2;
        }
        int y;
        if (h > availH) {
            y = clamp((viewH - h) / 2 + panY, viewH - margin - h, margin);
        } else {
            y = (viewH - h) / 2;
        }
        return new Rect(x, y, w, h);
    }

    private static int clamp(int v, int lo, int hi) {
        if (lo > hi) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}

package com.niuqu.chatbubble.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8 (§13 D3): inline images are always thumbnails and the full-screen preview
 * lays the image out aspect-fit. These are the pure numbers all three platforms
 * share; the tests are the contract they must agree on.
 */
class ImageGeometryTest {

    private static final int LONG = ImageGeometry.THUMBNAIL_MAX_LONG_EDGE;

    // ---- inline thumbnail sizing ----

    @Test
    void smallImageIsNeverUpscaled() {
        assertArrayEquals(new int[]{20, 10},
            ImageGeometry.thumbnailSize(20, 10, LONG, 400));
        assertArrayEquals(new int[]{1, 1},
            ImageGeometry.thumbnailSize(1, 1, LONG, 400));
    }

    @Test
    void exactLongEdgeIsKeptUnchanged() {
        assertArrayEquals(new int[]{180, 120},
            ImageGeometry.thumbnailSize(180, 120, LONG, 400));
    }

    @Test
    void wideLargeImageLongEdgeIsCapped() {
        // 1000x500 -> x0.18 -> 180x90, long edge == the token.
        assertArrayEquals(new int[]{180, 90},
            ImageGeometry.thumbnailSize(1000, 500, LONG, 1000));
    }

    @Test
    void tallLargeImageLongEdgeIsCapped() {
        assertArrayEquals(new int[]{90, 180},
            ImageGeometry.thumbnailSize(500, 1000, LONG, 1000));
    }

    @Test
    void squareLargeImageBecomesTokenSquare() {
        assertArrayEquals(new int[]{180, 180},
            ImageGeometry.thumbnailSize(1000, 1000, LONG, 1000));
    }

    @Test
    void aspectRatioIsPreserved() {
        // 4:3 at the cap is exactly 180x135.
        assertArrayEquals(new int[]{180, 135},
            ImageGeometry.thumbnailSize(4000, 3000, LONG, 1000));
    }

    @Test
    void availableWidthClampsWhenNarrowerThanTheToken() {
        // After the long-edge cap the thumbnail is 180x90; only 120px fit.
        assertArrayEquals(new int[]{120, 60},
            ImageGeometry.thumbnailSize(1000, 500, LONG, 120));
    }

    @Test
    void extremelyThinImageStaysOnePixelThick() {
        assertArrayEquals(new int[]{180, 1},
            ImageGeometry.thumbnailSize(10000, 1, LONG, 1000));
    }

    @Test
    void nonPositiveDimensionsYieldSafeOnePixel() {
        assertArrayEquals(new int[]{1, 1}, ImageGeometry.thumbnailSize(0, 10, LONG, 400));
        assertArrayEquals(new int[]{1, 1}, ImageGeometry.thumbnailSize(10, 0, LONG, 400));
        assertArrayEquals(new int[]{1, 1}, ImageGeometry.thumbnailSize(-5, -5, LONG, 400));
    }

    // ---- loading placeholder (aspect not yet known) ----

    @Test
    void placeholderIsAtMostTheTokenBox() {
        assertArrayEquals(new int[]{180, 180},
            ImageGeometry.thumbnailPlaceholder(LONG, 1000));
    }

    @Test
    void placeholderNeverExceedsAvailableWidth() {
        assertArrayEquals(new int[]{120, 120},
            ImageGeometry.thumbnailPlaceholder(LONG, 120));
    }

    @Test
    void placeholderDegeneratesSafely() {
        assertArrayEquals(new int[]{1, 1}, ImageGeometry.thumbnailPlaceholder(LONG, 0));
        assertArrayEquals(new int[]{1, 1}, ImageGeometry.thumbnailPlaceholder(0, 400));
    }

    // ---- full-screen preview layout ----

    @Test
    void largeImageIsFittedInsideTheVerticallyCentredBox() {
        ImageGeometry.Rect r = ImageGeometry.previewRect(2000, 1000, 800, 600, 8, 1f, 0, 0);
        // avail 784x584 -> scale 0.392 -> 784x392, centred.
        assertEquals(784, r.w());
        assertEquals(392, r.h());
        assertEquals(8, r.x());
        assertEquals((600 - 392) / 2, r.y());
    }

    @Test
    void smallImageIsUpscaledToFitTheView() {
        ImageGeometry.Rect r = ImageGeometry.previewRect(10, 10, 800, 600, 8, 1f, 0, 0);
        // min(784/10, 584/10) = 58.4 -> 584x584.
        assertEquals(584, r.w());
        assertEquals(584, r.h());
        assertEquals((800 - 584) / 2, r.x());
        assertEquals(8, r.y());
    }

    @Test
    void zoomInEnlargesAndKeepsCentredWhenUnpanned() {
        ImageGeometry.Rect r = ImageGeometry.previewRect(2000, 1000, 800, 600, 8, 2f, 0, 0);
        assertEquals(1568, r.w());
        assertEquals(784, r.h());
        assertEquals((800 - 1568) / 2, r.x());
        assertEquals((600 - 784) / 2, r.y());
    }

    @Test
    void panIsIgnoredWhenTheImageIsSmallerThanTheView() {
        ImageGeometry.Rect centred = ImageGeometry.previewRect(10, 10, 800, 600, 8, 1f, 0, 0);
        ImageGeometry.Rect dragged = ImageGeometry.previewRect(10, 10, 800, 600, 8, 1f, 999, -999);
        assertEquals(centred, dragged);
    }

    @Test
    void panStopsAtTheMarginWhenTheImageOverflows() {
        // Zoomed 2x the image is 1568x784; the left-most pan puts the left edge at the margin.
        ImageGeometry.Rect r = ImageGeometry.previewRect(2000, 1000, 800, 600, 8, 2f, 99999, 0);
        assertEquals(8, r.x());
        // And the right-most pan puts the right edge at (view - margin).
        ImageGeometry.Rect r2 = ImageGeometry.previewRect(2000, 1000, 800, 600, 8, 2f, -99999, 0);
        assertEquals(800 - 8, r2.x() + r2.w());
    }

    @Test
    void noPanCanEverOpenAGapOnTheOverflowingAxis() {
        for (int pan = -5000; pan <= 5000; pan += 137) {
            ImageGeometry.Rect r = ImageGeometry.previewRect(2000, 1000, 800, 600, 8, 2f, pan, 0);
            assertTrue(r.x() <= 8, "left edge left a gap at pan " + pan + ": x=" + r.x());
            assertTrue(r.x() + r.w() >= 800 - 8,
                "right edge left a gap at pan " + pan);
        }
    }

    @Test
    void zoomIsClampedToUsableBounds() {
        assertEquals(ImageGeometry.MAX_ZOOM, ImageGeometry.clampZoom(100f));
        assertEquals(ImageGeometry.MIN_ZOOM, ImageGeometry.clampZoom(0.01f));
        assertEquals(1f, ImageGeometry.clampZoom(Float.NaN));
        assertEquals(2f, ImageGeometry.clampZoom(2f));
    }

    @Test
    void degeneratePreviewStillReturnsAnOnScreenBox() {
        ImageGeometry.Rect r = ImageGeometry.previewRect(0, 0, 800, 600, 8, 1f, 0, 0);
        assertTrue(r.w() >= 1 && r.h() >= 1);
        assertTrue(r.x() >= 0 && r.y() >= 0);
        assertTrue(r.x() + r.w() <= 800 && r.y() + r.h() <= 600);
    }
}

package com.kodedu.component;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the viewport visible-page-range computation
 * used by {@link PdfPreviewPane#visiblePageRange}.
 *
 * <p>The contract: given a list of PDF page heights (in points), a
 * scroll {@code Vvalue} in [0,1], a viewport pixel height, the
 * current zoom, the inter-page spacing in pixels, and a prefetch
 * radius, return the inclusive [from, to] page index range that
 * should be rasterised.  Out-of-range scroll values clamp; degenerate
 * documents (zero pages) return an empty range.
 *
 * <p>BASE_DPI is mirrored from the production constant (96).  Page
 * pixel height is {@code heightPt * 96 / 72 * zoom}.
 */
class PdfPreviewPaneVisibleRangeTest {

    private static final double BASE_DPI = 96.0;
    private static final double SPACING = 8.0;
    /** US-letter portrait, 11in tall: 11 * 72 = 792pt -> 1056px at zoom=1. */
    private static final double PAGE_PT_HEIGHT = 792.0;

    private static List<Double> uniformPages(int n) {
        Double[] arr = new Double[n];
        Arrays.fill(arr, PAGE_PT_HEIGHT);
        return Arrays.asList(arr);
    }

    @Test
    void emptyDocReturnsEmptyRange() {
        int[] r = PdfPreviewPane.visiblePageRange(
                Collections.emptyList(), 0.0, 800, 1.0, SPACING, BASE_DPI, 2);
        assertEquals(0, r[0]);
        assertTrue(r[1] < r[0], "expected empty range, got [" + r[0] + ", " + r[1] + "]");
    }

    @Test
    void scrollAtTopReturnsFirstPagesOnly() {
        // Viewport height 800px holds <1 page; expect page 0 + prefetch.
        int[] r = PdfPreviewPane.visiblePageRange(
                uniformPages(50), 0.0, 800, 1.0, SPACING, BASE_DPI, 2);
        assertEquals(0, r[0], "must start at page 0 when scrolled to top");
        // 800px < one 1056px page so only page 0 visible; +2 prefetch -> [0, 2]
        assertArrayEquals(new int[] {0, 2}, r);
    }

    @Test
    void scrollAtBottomReturnsLastPagesOnly() {
        int[] r = PdfPreviewPane.visiblePageRange(
                uniformPages(50), 1.0, 800, 1.0, SPACING, BASE_DPI, 2);
        // The last page (49) is visible; +2 prefetch above clamped -> [47, 49]
        assertArrayEquals(new int[] {47, 49}, r);
    }

    @Test
    void midScrollPicksCorrectVisibleWindow() {
        // 50 pages, each ~1064px (1056 + 8 spacing).  Total ~53200px,
        // viewport 1500px (~1.5 pages).  Vvalue=0.5 puts the viewport
        // top around (53200 - 1500) * 0.5 = 25850px, which is page 24.
        int[] r = PdfPreviewPane.visiblePageRange(
                uniformPages(50), 0.5, 1500, 1.0, SPACING, BASE_DPI, 2);
        assertTrue(r[0] >= 22 && r[0] <= 25,
                "expected from in [22,25], got " + r[0]);
        assertTrue(r[1] >= r[0] && r[1] <= r[0] + 5,
                "expected window of <=5 pages with prefetch, got [" + r[0] + ", " + r[1] + "]");
    }

    @Test
    void prefetchRadiusZeroReturnsTightWindow() {
        int[] r = PdfPreviewPane.visiblePageRange(
                uniformPages(50), 0.0, 800, 1.0, SPACING, BASE_DPI, 0);
        // Page 0 only (viewport < 1 page tall, no prefetch).
        assertArrayEquals(new int[] {0, 0}, r);
    }

    @Test
    void prefetchClampsAtDocEnds() {
        int[] r = PdfPreviewPane.visiblePageRange(
                uniformPages(3), 1.0, 800, 1.0, SPACING, BASE_DPI, 5);
        assertEquals(0, r[0], "prefetch must not push from below 0");
        assertEquals(2, r[1], "prefetch must not push to above pageCount-1");
    }

    @Test
    void zoomChangesVisibleWindow() {
        // With a fixed viewport height, lower zoom packs more pages
        // into the viewport than higher zoom.  At zoom=1 each page is
        // 1056px tall; a 2400px viewport spans ~2.3 pages.  At zoom=2
        // the same viewport spans ~1.13 pages.
        int[] zoomLow = PdfPreviewPane.visiblePageRange(
                uniformPages(50), 0.5, 2400, 1.0, SPACING, BASE_DPI, 0);
        int[] zoomHigh = PdfPreviewPane.visiblePageRange(
                uniformPages(50), 0.5, 2400, 2.0, SPACING, BASE_DPI, 0);
        int lowSpan = zoomLow[1] - zoomLow[0];
        int highSpan = zoomHigh[1] - zoomHigh[0];
        assertTrue(lowSpan > highSpan,
                "lower zoom must span more pages: zoomLow="
                        + Arrays.toString(zoomLow) + " span=" + lowSpan
                        + " zoomHigh=" + Arrays.toString(zoomHigh) + " span=" + highSpan);
    }

    @Test
    void singlePageDocAlwaysReturnsPageZero() {
        int[] r = PdfPreviewPane.visiblePageRange(
                uniformPages(1), 0.5, 1500, 1.0, SPACING, BASE_DPI, 5);
        assertArrayEquals(new int[] {0, 0}, r);
    }

    @Test
    void rangeNeverExtendsBeyondPageCount() {
        for (int pages : new int[] {1, 5, 50, 200}) {
            for (double v = 0; v <= 1.0; v += 0.1) {
                int[] r = PdfPreviewPane.visiblePageRange(
                        uniformPages(pages), v, 800, 1.0, SPACING, BASE_DPI, 2);
                assertTrue(r[0] >= 0,
                        "from < 0 (pages=" + pages + ", v=" + v + "): " + Arrays.toString(r));
                assertTrue(r[1] < pages,
                        "to >= pages (pages=" + pages + ", v=" + v + "): " + Arrays.toString(r));
                assertTrue(r[0] <= r[1],
                        "from > to (pages=" + pages + ", v=" + v + "): " + Arrays.toString(r));
            }
        }
    }

    @Test
    void variableHeightPagesAreHandled() {
        // Mixed letter (792pt) + a3-portrait (1190pt).  Just sanity
        // check that the walker produces a consistent window.
        List<Double> heights = Arrays.asList(792.0, 1190.0, 792.0, 1190.0, 792.0);
        int[] r = PdfPreviewPane.visiblePageRange(
                heights, 0.0, 1000, 1.0, SPACING, BASE_DPI, 1);
        assertEquals(0, r[0]);
        assertTrue(r[1] >= 0 && r[1] < heights.size());
    }
}

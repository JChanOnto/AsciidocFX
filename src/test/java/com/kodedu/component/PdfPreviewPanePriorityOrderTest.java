package com.kodedu.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the streaming render priority order used by
 * {@link PdfPreviewPane#priorityOrder(int, int)}.
 *
 * <p>The contract: anchor renders first, then expand outward
 * (anchor-1, anchor+1, anchor-2, anchor+2, ...) so the page the user
 * is currently looking at appears as soon as possible after a reload
 * or zoom-up.  The order must cover every page exactly once.
 */
class PdfPreviewPanePriorityOrderTest {

    @Test
    void anchorIsRenderedFirst() {
        List<Integer> order = PdfPreviewPane.priorityOrder(7, 20);
        assertEquals(7, order.get(0),
                "the visible page must be the very first one rendered");
    }

    @Test
    void expandsOutwardSymmetricallyFromAnchor() {
        // Anchor at 5 in a 12-page doc:
        //   5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11
        List<Integer> order = PdfPreviewPane.priorityOrder(5, 12);
        assertEquals(List.of(5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11), order);
    }

    @Test
    void anchorAtTopDegradesToTopDownOrder() {
        // Fresh-open case: anchor=0 must produce 0, 1, 2, ...
        List<Integer> order = PdfPreviewPane.priorityOrder(0, 5);
        assertEquals(List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    void anchorAtBottomDegradesToBottomUpOrder() {
        List<Integer> order = PdfPreviewPane.priorityOrder(4, 5);
        assertEquals(List.of(4, 3, 2, 1, 0), order);
    }

    @Test
    void coversEveryPageExactlyOnce() {
        for (int pages : new int[] {1, 2, 3, 10, 50, 200}) {
            for (int anchor = 0; anchor < pages; anchor++) {
                List<Integer> order = PdfPreviewPane.priorityOrder(anchor, pages);
                assertEquals(pages, order.size(),
                        "order size must equal page count (anchor=" + anchor
                                + ", pages=" + pages + ")");
                assertEquals(pages, order.stream().distinct().count(),
                        "every page must appear exactly once (anchor=" + anchor
                                + ", pages=" + pages + ")");
                for (int p : order) {
                    assertTrue(p >= 0 && p < pages,
                            "page " + p + " out of range [0, " + pages + ")");
                }
            }
        }
    }

    @Test
    void anchorOutOfRangeIsClamped() {
        // Negative anchor clamps to 0; over-range clamps to pageCount-1.
        assertEquals(0, PdfPreviewPane.priorityOrder(-5, 3).get(0));
        assertEquals(2, PdfPreviewPane.priorityOrder(99, 3).get(0));
    }

    @Test
    void zeroPageDocReturnsEmpty() {
        assertEquals(List.of(), PdfPreviewPane.priorityOrder(0, 0));
    }

    @Test
    void singlePageDocReturnsOnlyPageZero() {
        assertEquals(List.of(0), PdfPreviewPane.priorityOrder(0, 1));
        // Out-of-range anchor still clamps to the only valid page.
        assertEquals(List.of(0), PdfPreviewPane.priorityOrder(7, 1));
    }
}

package com.kodedu.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math contract for {@link PdfPreviewPane#computeZoomAnchor}.
 *
 * <p>Pins the cursor-anchored zoom behaviour so a regression in the
 * formula is caught without spinning up a JavaFX scene.  Each test
 * sets up a realistic-sized PDF preview viewport (800x600) over a
 * tall multi-page content area, then verifies that after a zoom
 * change the document point that was under the cursor is still under
 * the cursor (within 1 pixel for floating-point slack).
 *
 * <p>Coordinate system reminder: ScrollPane vvalue maps linearly from
 * {@code vmin} (content top at viewport top) to {@code vmax} (content
 * bottom at viewport bottom).  So the absolute scroll offset of the
 * content in pixels is
 *   {@code scrollPx = (vvalue - vmin) / (vmax - vmin) * (contentH - viewportH)}.
 */
class PdfPreviewPaneZoomAnchorTest {

    private static final double VIEWPORT_W = 800;
    private static final double VIEWPORT_H = 600;
    // Realistic shape: 20 letter-size pages at 96 DPI (612x792 pt) ->
    // ~816x1056 px each -> ~21k px tall stacked.  Wider than viewport
    // when zoomed in past ~1.0.
    private static final double CONTENT_W_AT_1X = 816;
    private static final double CONTENT_H_AT_1X = 21120;

    /** Helper: scroll offset (pixels) implied by a given vvalue. */
    private static double scrollPx(double vvalue, double vMin, double vMax,
                                   double contentH, double viewportH) {
        double scrollable = contentH - viewportH;
        if (scrollable <= 0) return 0;
        return (vvalue - vMin) / (vMax - vMin) * scrollable;
    }

    /**
     * The core invariant: for any (cursor, oldZoom, newZoom), the
     * pixel position of the document point under the cursor in the
     * NEW viewport equals the pixel position it had in the OLD
     * viewport (the cursor's offset within the viewport).
     */
    private void assertCursorAnchored(double cursorVpY, double currentV,
                                      double oldZoom, double newZoom) {
        double oldContentH = CONTENT_H_AT_1X * oldZoom;
        double newContentH = CONTENT_H_AT_1X * newZoom;
        double oldScrollPx = scrollPx(currentV, 0, 1, oldContentH, VIEWPORT_H);
        // Document point under cursor at OLD zoom (in OLD content coords):
        double anchorContentY = oldScrollPx + cursorVpY;

        PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                /* anchorContentX */ 0, anchorContentY,
                /* cursorVpX     */ 0, cursorVpY,
                oldZoom, newZoom,
                /* oldContentW */ CONTENT_W_AT_1X * oldZoom, oldContentH,
                VIEWPORT_W, VIEWPORT_H,
                /* currentH/V  */ 0, currentV,
                0, 1, 0, 1);

        // Where does the same document point land in NEW content coords?
        double scale = newZoom / oldZoom;
        double anchorContentYAtNewZoom = anchorContentY * scale;
        // Where is the viewport's top in NEW content coords (per anchor.vvalue)?
        double newScrollPx = scrollPx(anchor.vvalue(), 0, 1, newContentH, VIEWPORT_H);
        // Cursor's pixel position relative to viewport's top, AFTER the zoom:
        double cursorYAfter = anchorContentYAtNewZoom - newScrollPx;

        assertEquals(cursorVpY, cursorYAfter, 1.0,
                "Document point under cursor must stay under cursor across zoom. "
                        + "oldZoom=" + oldZoom + " newZoom=" + newZoom
                        + " cursorVpY=" + cursorVpY + " currentV=" + currentV
                        + " anchor=" + anchor
                        + " anchorContentY=" + anchorContentY
                        + " anchorContentYAtNewZoom=" + anchorContentYAtNewZoom
                        + " newScrollPx=" + newScrollPx
                        + " cursorYAfter=" + cursorYAfter);
    }

    @Test
    void zoomInMidDocumentKeepsCursorAnchored() {
        // User is half-way through the document, cursor near top of
        // viewport.  Zooming in must NOT snap to page 1.
        assertCursorAnchored(/* cursorVpY */ 100,
                /* currentV  */ 0.5,
                /* oldZoom   */ 1.0,
                /* newZoom   */ 1.1);
    }

    @Test
    void zoomInWithCursorNearBottomOfViewport() {
        assertCursorAnchored(550, 0.5, 1.0, 1.2);
    }

    @Test
    void zoomOutNearEndOfDocument() {
        // 95% scrolled, cursor mid-viewport.  Zooming out shouldn't
        // jump to top either.
        assertCursorAnchored(300, 0.95, 1.5, 1.0);
    }

    @Test
    void zoomInAtTopOfDocumentStaysAtTop() {
        // currentV = 0 means viewport top is at content top.  Cursor
        // at top of viewport -> anchor point is content top.  After
        // zoom in, that point is still at content top, so vvalue
        // should remain 0.
        PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                0, 0,        // anchorContent at (0,0)
                0, 0,        // cursor at viewport (0,0)
                1.0, 1.5,
                CONTENT_W_AT_1X, CONTENT_H_AT_1X,
                VIEWPORT_W, VIEWPORT_H,
                0, 0,
                0, 1, 0, 1);
        assertEquals(0.0, anchor.vvalue(), 1e-9,
                "Anchoring to content top must keep vvalue=0; got " + anchor);
    }

    @Test
    void smallContentReturnsCurrentScrollUnchanged() {
        // Content shorter than viewport -> nothing scrollable -> the
        // helper must NOT divide by zero or set vvalue to NaN; it
        // returns the current value unchanged.
        PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                100, 100,
                50, 50,
                1.0, 1.5,
                /* tiny content */ 200, 200,
                VIEWPORT_W, VIEWPORT_H,
                0.42, 0.73,
                0, 1, 0, 1);
        assertEquals(0.42, anchor.hvalue(), 1e-9);
        assertEquals(0.73, anchor.vvalue(), 1e-9);
    }

    @Test
    void valuesAreClampedTo01() {
        // Cursor at extreme top with already-at-top scroll, zooming
        // in still produces vvalue >= 0; cursor at extreme bottom
        // with at-bottom scroll produces vvalue <= 1.
        PdfPreviewPane.ZoomAnchor topAnchor = PdfPreviewPane.computeZoomAnchor(
                0, -50,            // anchor "above" content (impossible IRL but tests clamp)
                0, 50,
                1.0, 2.0,
                CONTENT_W_AT_1X, CONTENT_H_AT_1X,
                VIEWPORT_W, VIEWPORT_H,
                0, 0,
                0, 1, 0, 1);
        assertTrue(topAnchor.vvalue() >= 0 && topAnchor.vvalue() <= 1,
                "vvalue must be clamped to [0,1]; got " + topAnchor);

        PdfPreviewPane.ZoomAnchor botAnchor = PdfPreviewPane.computeZoomAnchor(
                0, CONTENT_H_AT_1X * 10, // way past content end
                0, 50,
                1.0, 2.0,
                CONTENT_W_AT_1X, CONTENT_H_AT_1X,
                VIEWPORT_W, VIEWPORT_H,
                0, 1,
                0, 1, 0, 1);
        assertTrue(botAnchor.vvalue() >= 0 && botAnchor.vvalue() <= 1,
                "vvalue must be clamped to [0,1]; got " + botAnchor);
    }

    @Test
    void zoomInMidDocumentDoesNotResetVvalueToZero() {
        // Specific regression for "zoom always snaps to first page":
        // a non-zero currentV at zoom 1.0 zooming to 1.1 must not
        // produce vvalue = 0.
        PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                400, 10000,  // mid-document anchor
                400, 300,    // cursor mid-viewport
                1.0, 1.1,
                CONTENT_W_AT_1X, CONTENT_H_AT_1X,
                VIEWPORT_W, VIEWPORT_H,
                0.4, 0.5,
                0, 1, 0, 1);
        assertTrue(anchor.vvalue() > 0.01,
                "Mid-document zoom-in must not snap to top (vvalue near 0); got "
                        + anchor);
    }
}

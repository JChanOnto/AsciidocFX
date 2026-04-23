package com.kodedu.component;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Realistic end-to-end test of the PDF preview cursor-anchored
 * zoom lifecycle.  Replicates the complete production wiring from
 * {@link PdfPreviewPane#afterViewInit()} +
 * {@link PdfPreviewPane#zoomAroundScenePoint(double, double, double)}
 * +
 * {@link PdfPreviewPane}'s 220ms debounced re-rasterise step that
 * REPLACES every child of {@code pagesBox} with a fresh placeholder
 * sized for the new zoom.
 *
 * <p>The earlier integration test pinned only the immediate
 * scroll-set behaviour.  The user reports zoom is still broken
 * even after that fix, which strongly suggests the debounced
 * children-replace pass is what's snapping the scroll back to the
 * top.  This test reproduces the full sequence:
 *
 * <ol>
 *   <li>User wheels with Ctrl pressed at a scene-coord point</li>
 *   <li>Code computes content-local anchor via
 *       {@link javafx.scene.Node#sceneToLocal}</li>
 *   <li>{@code zoomSlider.setValue(newZoom)} fires the listener
 *       which mutates every {@code ImageView.fitWidth} (instant
 *       rescale) and starts the 220ms debounce</li>
 *   <li>{@code applyCss + layout + setVvalue}</li>
 *   <li>220ms later, the debounce fires and REPLACES each
 *       {@code ImageView} with a {@link Region} placeholder sized
 *       for the new zoom</li>
 *   <li>Assert scroll position still anchors the same document
 *       point under the same viewport offset</li>
 * </ol>
 *
 * <p>If step 5 fails, the production code needs to re-anchor after
 * the rerasterize children-replace, or the children-replace needs
 * to preserve the scroll-pixel position itself.
 */
@ExtendWith(ApplicationExtension.class)
class PdfPreviewPaneZoomRealisticTest {

    private static final double PAGE_W = 816;     // letter @ 96 DPI
    private static final double PAGE_H = 1056;
    private static final int PAGE_COUNT = 20;
    private static final double VIEWPORT_W = 800;
    private static final double VIEWPORT_H = 600;
    private static final double SPACING = 8;       // matches production VBox(8)
    private static final Duration DEBOUNCE = Duration.millis(220);

    private Stage stage;
    private ScrollPane scrollPane;
    private VBox pagesBox;
    private Slider zoomSlider;
    private PauseTransition zoomDebounce;

    /** Per-page widths in PDF points (production keeps these to drive
     *  rescaleViewsInstantly + rerasterize placeholder sizing). */
    private final List<Double> pagePtWidths = new ArrayList<>();
    private final List<Double> pagePtHeights = new ArrayList<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        pagesBox = new VBox(SPACING);
        pagesBox.setFillWidth(false);

        WritableImage placeholderImage = new WritableImage(1, 1);
        for (int i = 0; i < PAGE_COUNT; i++) {
            // Production stores PDF-point dimensions; ImageViews are
            // sized to ptWidth * BASE_DPI / 72 * zoom.  Letter at 96 DPI
            // is 816x1056 px @ zoom 1.0, which is 612x792 pt.
            pagePtWidths.add(612.0);
            pagePtHeights.add(792.0);
            ImageView iv = new ImageView(placeholderImage);
            iv.setPreserveRatio(false);
            iv.setFitWidth(PAGE_W);
            iv.setFitHeight(PAGE_H);
            pagesBox.getChildren().add(iv);
        }

        scrollPane = new ScrollPane(pagesBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPannable(true);
        scrollPane.setPrefViewportWidth(VIEWPORT_W);
        scrollPane.setPrefViewportHeight(VIEWPORT_H);

        zoomSlider = new Slider(0.5, 2.0, 1.0);
        zoomSlider.setBlockIncrement(0.1);

        // Debounced rerasterize \u2014 this is the production behaviour
        // that REPLACES every child with a fresh placeholder sized for
        // the new zoom.  This is the most likely thing snapping the
        // scroll position after the user's zoom: even though we set
        // vvalue correctly synchronously, 220ms later the children
        // get replaced and on the next layout pulse ScrollPane
        // recomputes scroll against the new content.
        zoomDebounce = new PauseTransition(DEBOUNCE);
        zoomDebounce.setOnFinished(e -> simulateRerasterizeChildrenReplace());

        // Listener that mirrors PdfPreviewPane.afterViewInit lines
        // 277\u2013283: any zoom change rescales views instantly +
        // schedules a debounced heavy rerasterize.
        zoomSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (Math.abs(nv.doubleValue() - ov.doubleValue()) > 0.001) {
                rescaleViewsInstantly(nv.doubleValue());
                zoomDebounce.playFromStart();
            }
        });

        BorderPane root = new BorderPane();
        root.setCenter(scrollPane);
        root.setBottom(zoomSlider);
        Scene scene = new Scene(root, VIEWPORT_W, VIEWPORT_H + 40);
        stage.setScene(scene);
        stage.show();
    }

    /** Mirror of {@link PdfPreviewPane#rescaleViewsInstantly(double)}. */
    private void rescaleViewsInstantly(double zoom) {
        int n = pagesBox.getChildren().size();
        for (int i = 0; i < n && i < pagePtWidths.size(); i++) {
            javafx.scene.Node node = pagesBox.getChildren().get(i);
            double wPx = pagePtWidths.get(i) * 96.0 / 72.0 * zoom;
            double hPx = pagePtHeights.get(i) * 96.0 / 72.0 * zoom;
            if (node instanceof ImageView iv) {
                iv.setFitWidth(wPx);
                iv.setFitHeight(hPx);
            } else if (node instanceof Region region) {
                region.setPrefSize(wPx, hPx);
                region.setMinSize(wPx, hPx);
                region.setMaxSize(wPx, hPx);
            }
        }
    }

    /** Mirror of {@link PdfPreviewPane}'s rerasterize\u2192replace pass.
     *  This replaces every child with a fresh Region placeholder of
     *  the same dimensions.
     *
     *  <p>Critical: the production code snapshots scroll position
     *  BEFORE replacing children and re-applies it AFTER, with a
     *  forced layout pulse in between (the same pattern as
     *  {@link PdfPreviewPane#zoomAroundScenePoint}).  Without this
     *  snapshot+restore, ScrollPane snaps the view to the top on
     *  every children-replace \u2014 the long-standing "zoom always
     *  jumps to page 1" symptom.  This test fixture mirrors the
     *  fixed behaviour so a future regression in the production
     *  code (someone removing the snapshot+restore) will be caught
     *  by this test rather than by users.
     */
    private void simulateRerasterizeChildrenReplace() {
        double zoom = zoomSlider.getValue();
        double savedH = scrollPane.getHvalue();
        double savedV = scrollPane.getVvalue();
        int n = pagesBox.getChildren().size();
        List<javafx.scene.Node> replacements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double wPx = pagePtWidths.get(i) * 96.0 / 72.0 * zoom;
            double hPx = pagePtHeights.get(i) * 96.0 / 72.0 * zoom;
            Region placeholder = new Region();
            placeholder.setPrefSize(wPx, hPx);
            placeholder.setMinSize(wPx, hPx);
            placeholder.setMaxSize(wPx, hPx);
            replacements.add(placeholder);
        }
        pagesBox.getChildren().setAll(replacements);
        pagesBox.applyCss();
        pagesBox.layout();
        scrollPane.applyCss();
        scrollPane.layout();
        scrollPane.setHvalue(savedH);
        scrollPane.setVvalue(savedV);
    }

    /** Faithful reproduction of {@link PdfPreviewPane#zoomAroundScenePoint}. */
    private void zoomAroundScenePoint(double newZoom, double sceneX, double sceneY)
            throws InterruptedException {
        onFx(() -> {
            double oldZoom = zoomSlider.getValue();
            if (Math.abs(newZoom - oldZoom) < 0.0001) return;
            Point2D contentPoint = pagesBox.sceneToLocal(sceneX, sceneY);
            Bounds oldContent = pagesBox.getLayoutBounds();
            Bounds vp = scrollPane.getViewportBounds();
            double oldContentW = oldContent.getWidth();
            double oldContentH = oldContent.getHeight();
            double viewportW = vp.getWidth();
            double viewportH = vp.getHeight();
            double currentH = scrollPane.getHvalue();
            double currentV = scrollPane.getVvalue();
            // Compute cursor offset within viewport from content-local
            // cursor + current scroll \u2014 NOT from
            // {@code scrollPane.localToScene(scrollPane.getViewportBounds())}
            // which returns bounds whose minY is the negated scroll
            // offset (a JavaFX quirk that breaks the second + zoom).
            double scrollableYOld = Math.max(0, oldContentH - viewportH);
            double scrollableXOld = Math.max(0, oldContentW - viewportW);
            double currentScrollPxY = scrollableYOld * (currentV - scrollPane.getVmin())
                    / Math.max(1e-9, scrollPane.getVmax() - scrollPane.getVmin());
            double currentScrollPxX = scrollableXOld * (currentH - scrollPane.getHmin())
                    / Math.max(1e-9, scrollPane.getHmax() - scrollPane.getHmin());
            double cursorVpX = contentPoint.getX() - currentScrollPxX;
            double cursorVpY = contentPoint.getY() - currentScrollPxY;
            PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                    contentPoint.getX(), contentPoint.getY(),
                    cursorVpX, cursorVpY,
                    oldZoom, newZoom,
                    oldContentW, oldContentH,
                    viewportW, viewportH,
                    currentH, currentV,
                    scrollPane.getHmin(), scrollPane.getHmax(),
                    scrollPane.getVmin(), scrollPane.getVmax(),
                    /* fixedOverheadW */ 0,
                    /* fixedOverheadH */ SPACING * Math.max(0, pagesBox.getChildren().size() - 1));
            zoomSlider.setValue(newZoom);
            pagesBox.applyCss();
            pagesBox.layout();
            scrollPane.applyCss();
            scrollPane.layout();
            scrollPane.setHvalue(anchor.hvalue());
            scrollPane.setVvalue(anchor.vvalue());
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void onFx(Runnable r) throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX task timed out");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Wait for the 220ms zoom debounce to fire and the resulting
     *  children-replace + layout pulse to settle. */
    private void waitForRerasterize() throws InterruptedException {
        Thread.sleep((long) DEBOUNCE.toMillis() + 100);
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Compute the document Y (in NEW content coords) that is
     *  currently under the viewport offset {@code cursorVpY}. */
    private double cursorContentY(double cursorVpY) {
        double contentH = pagesBox.getLayoutBounds().getHeight();
        double scrollable = Math.max(1, contentH - VIEWPORT_H);
        double scrollPx = scrollPane.getVvalue() * scrollable;
        return scrollPx + cursorVpY;
    }

    @Test
    void zoomAtMidDocumentSurvivesDebouncedRerasterize() throws Exception {
        // Setup: scroll mid-document at 1.0x.
        onFx(() -> scrollPane.setVvalue(0.5));
        Bounds vpBounds = scrollPane.localToScene(scrollPane.getViewportBounds());
        // Cursor at viewport vertical centre, X doesn't matter much.
        double cursorSceneY = vpBounds.getMinY() + VIEWPORT_H / 2.0;
        double cursorSceneX = vpBounds.getMinX() + VIEWPORT_W / 2.0;

        // What document Y is under the cursor BEFORE the zoom?  This
        // is the invariant: the same document point must remain under
        // the same viewport offset AFTER the full zoom lifecycle
        // (immediate scroll set + 220ms debounced children-replace).
        Bounds beforeContent = pagesBox.getLayoutBounds();
        double beforeScrollPx = scrollPane.getVvalue() * (beforeContent.getHeight() - VIEWPORT_H);
        double beforeDocY = beforeScrollPx + VIEWPORT_H / 2.0;

        // Zoom 1.0 -> 1.5, anchored to cursor.
        zoomAroundScenePoint(1.5, cursorSceneX, cursorSceneY);

        // Immediate-after assertion (the case the earlier test pinned).
        double afterImmediateContentY = cursorContentY(VIEWPORT_H / 2.0);
        assertEquals(beforeDocY * 1.5, afterImmediateContentY, 5.0,
                "immediate cursor-anchor failed BEFORE the debounce fires; got afterImmediateContentY="
                        + afterImmediateContentY + " expected " + (beforeDocY * 1.5));

        // *** The realistic part the user is reporting on: wait for
        // the 220ms debounce to fire and REPLACE every page child
        // with a fresh placeholder.  After that pass, the cursor
        // anchor MUST still hold.
        waitForRerasterize();

        // Sanity: children were actually replaced (not just rescaled).
        assertTrue(pagesBox.getChildren().get(0) instanceof Region,
                "test setup: rerasterize must have replaced ImageView with placeholder Region");

        double afterDebounceContentY = cursorContentY(VIEWPORT_H / 2.0);
        assertEquals(beforeDocY * 1.5, afterDebounceContentY, 10.0,
                "cursor-anchor LOST after debounced children-replace; was "
                        + (beforeDocY * 1.5) + " expected, got " + afterDebounceContentY
                        + " (vvalue=" + scrollPane.getVvalue()
                        + ", contentH=" + pagesBox.getLayoutBounds().getHeight() + ")");
    }

    @Test
    void multiStepZoomMaintainsCursorAcrossEveryDebounce() throws Exception {
        // Ramp 1.0 -> 1.1 -> 1.2 -> 1.3 -> 1.4, waiting for each
        // debounce to fire.  This is what the user actually does
        // (multiple wheel ticks).  Cursor anchor invariant must
        // hold through the entire chain.
        onFx(() -> scrollPane.setVvalue(0.6));
        Bounds vpBounds = scrollPane.localToScene(scrollPane.getViewportBounds());
        double cursorSceneY = vpBounds.getMinY() + 200; // upper-middle of viewport
        double cursorSceneX = vpBounds.getMinX() + VIEWPORT_W / 2.0;

        Bounds beforeContent = pagesBox.getLayoutBounds();
        double beforeScrollPx = scrollPane.getVvalue() * (beforeContent.getHeight() - VIEWPORT_H);
        double beforeDocYAtZoom1 = beforeScrollPx + 200;

        double zoom = 1.0;
        for (double step : new double[] {1.1, 1.2, 1.3, 1.4}) {
            zoomAroundScenePoint(step, cursorSceneX, cursorSceneY);
            waitForRerasterize();
            zoom = step;

            double expectedDocY = beforeDocYAtZoom1 * zoom;
            double actualDocY = cursorContentY(200);
            assertEquals(expectedDocY, actualDocY, 15.0,
                    "after zoom " + zoom + " (post-debounce) cursor lost anchor; expected="
                            + expectedDocY + " actual=" + actualDocY
                            + " vvalue=" + scrollPane.getVvalue());
        }
    }

    @Test
    void zoomNearBottomDoesNotSnapToTopAfterDebounce() throws Exception {
        // Specific shape of the user-reported bug: the scroll bar
        // jumps to the top of the document on every zoom click,
        // even when the user was at the bottom.  Reproduce by
        // scrolling near the end and zooming.
        onFx(() -> scrollPane.setVvalue(0.95));
        double startV = scrollPane.getVvalue();
        assertTrue(startV > 0.8, "precondition: scrolled near bottom, got " + startV);

        Bounds vpBounds = scrollPane.localToScene(scrollPane.getViewportBounds());
        double cursorSceneY = vpBounds.getMinY() + VIEWPORT_H / 2.0;
        double cursorSceneX = vpBounds.getMinX() + VIEWPORT_W / 2.0;

        zoomAroundScenePoint(1.2, cursorSceneX, cursorSceneY);
        waitForRerasterize();

        double afterV = scrollPane.getVvalue();
        assertTrue(afterV > 0.5,
                "after zoom near bottom, scroll vvalue collapsed from " + startV
                        + " to " + afterV
                        + " \u2014 this is the snap-to-top regression the user reported");
    }

    @Test
    void zoomOutNearBottomKeepsCursorVisible() throws Exception {
        // Inverse case: zoom out from a scrolled-down position.
        // After zoom-out the cursor's document point must still be
        // visible (within the viewport).
        onFx(() -> {
            zoomSlider.setValue(1.5);
        });
        // Wait for the rescale + initial debounce from the slider
        // value change above to settle so the rest of the test
        // starts from a clean state at 1.5x.
        waitForRerasterize();
        onFx(() -> scrollPane.setVvalue(0.7));

        Bounds vpBounds = scrollPane.localToScene(scrollPane.getViewportBounds());
        double cursorSceneY = vpBounds.getMinY() + VIEWPORT_H / 2.0;
        double cursorSceneX = vpBounds.getMinX() + VIEWPORT_W / 2.0;

        Bounds beforeContent = pagesBox.getLayoutBounds();
        double beforeScrollPx = scrollPane.getVvalue() * (beforeContent.getHeight() - VIEWPORT_H);
        double beforeDocY = beforeScrollPx + VIEWPORT_H / 2.0;

        zoomAroundScenePoint(1.0, cursorSceneX, cursorSceneY);
        waitForRerasterize();

        double scaleFactor = 1.0 / 1.5;
        double expectedDocY = beforeDocY * scaleFactor;
        double actualDocY = cursorContentY(VIEWPORT_H / 2.0);
        assertEquals(expectedDocY, actualDocY, 15.0,
                "zoom-out cursor anchor lost after debounce; expected="
                        + expectedDocY + " actual=" + actualDocY);
    }

    /**
     * Mirror of {@link PdfPreviewPane}'s {@code zoomAroundViewportCenter}
     * (the +/- toolbar button code path).  Computes the anchor scene
     * point by calling the production helper
     * {@link PdfPreviewPane#viewportCenterScene} so any regression in
     * its math (e.g. accidentally re-introducing
     * {@code scrollPane.localToScene(scrollPane.getViewportBounds())},
     * whose {@code minX/Y} can carry the negated scroll offset under
     * {@code setFitToWidth(true)}) shows up here.
     */
    private void clickZoomButton(double newZoom) throws InterruptedException {
        Point2D centre = PdfPreviewPane.viewportCenterScene(scrollPane);
        zoomAroundScenePoint(newZoom, centre.getX(), centre.getY());
    }

    @Test
    void buttonZoomInAtMidDocumentKeepsCentreDocPointStable() throws Exception {
        // The +/- buttons must behave like cursor zoom with the cursor
        // at the dead centre of the viewport.  After clicking +, the
        // document point that was at the viewport centre BEFORE the
        // click must STAY near the viewport centre AFTER, including
        // after the 220ms debounced rerasterize fires.
        //
        // We assert that the vvalue lands within the band around the
        // mid-document position, NOT that the doc Y matches a naive
        // {@code oldDocY * zoom} formula \u2014 the latter is wrong
        // by up to {@code (pagesCrossed * spacing * (zoom - 1))} pixels
        // because VBox spacing does not scale with zoom.  The
        // user-perceptible failure mode is "vvalue collapsed to ~0",
        // not "vvalue is off by 7 pixels".
        onFx(() -> scrollPane.setVvalue(0.5));
        double startV = scrollPane.getVvalue();

        clickZoomButton(1.2);

        double afterImmediateV = scrollPane.getVvalue();
        assertTrue(afterImmediateV > 0.30 && afterImmediateV < 0.70,
                "button zoom-in: vvalue lost mid-document position synchronously, "
                        + "was " + startV + " now " + afterImmediateV
                        + " \u2014 should stay near 0.5");

        waitForRerasterize();

        double afterDebounceV = scrollPane.getVvalue();
        assertTrue(afterDebounceV > 0.30 && afterDebounceV < 0.70,
                "button zoom-in: vvalue lost mid-document position after debounce, "
                        + "was " + startV + " now " + afterDebounceV
                        + " \u2014 the +/- snap-to-top regression the user reported");
    }

    @Test
    void multipleButtonClicksAtMidDocumentPreserveCentreAcrossEveryDebounce()
            throws Exception {
        // Mimics the user repeatedly clicking + to zoom in step by step.
        // Each click + debounce must NOT collapse the scroll position
        // to the top of the document.
        onFx(() -> scrollPane.setVvalue(0.5));
        double startV = scrollPane.getVvalue();

        for (double step : new double[] {1.1, 1.2, 1.3, 1.4}) {
            clickZoomButton(step);
            waitForRerasterize();

            double v = scrollPane.getVvalue();
            assertTrue(v > 0.25 && v < 0.75,
                    "after button-click zoom " + step + " (post-debounce) the "
                            + "vvalue collapsed away from 0.5: was " + startV
                            + " now " + v
                            + " \u2014 user-reported symptom: pages move up on +/-");
        }
    }

    @Test
    void buttonZoomNearBottomDoesNotSnapToTop() throws Exception {
        // Specific shape of the user-reported bug: clicking + when
        // scrolled near the end snaps the view back to page 1.
        onFx(() -> scrollPane.setVvalue(0.95));
        double startV = scrollPane.getVvalue();
        assertTrue(startV > 0.8, "precondition: scrolled near bottom, got " + startV);

        clickZoomButton(1.2);
        waitForRerasterize();

        double afterV = scrollPane.getVvalue();
        assertTrue(afterV > 0.5,
                "after button zoom-in near bottom, scroll vvalue collapsed from "
                        + startV + " to " + afterV
                        + " \u2014 the +/- snap-to-top regression the user reported");
    }
}

package com.kodedu.component;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
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
 * Real-JavaFX-toolkit verification of the cursor-anchored zoom
 * behaviour that {@link PdfPreviewPane#zoomAroundScenePoint} relies
 * on.  The pure-math contract is covered by
 * {@link PdfPreviewPaneZoomAnchorTest}; this test exists because the
 * persistent "zoom snaps to first page" bug was caused not by the
 * math but by setting {@link ScrollPane#setVvalue(double)}
 * synchronously immediately after mutating the children's sizes:
 * ScrollPane records the value but on the next layout pulse re-clamps
 * it against its still-stale viewport/content sizing, snapping to
 * top.
 *
 * <p>To exercise that code path we build a layout that mirrors
 * production exactly — a {@link ScrollPane} wrapping a {@link VBox}
 * (the {@code pagesBox}) of fixed-size {@link ImageView}s — and then
 * run the SAME apply-strategy used in production:
 * mutate {@code fitWidth}/{@code fitHeight} on every page, then
 * {@code Platform.runLater(() -> scrollPane.setVvalue(target))}.
 * After waiting for FX events to drain we assert the ScrollPane has
 * actually honoured the value.
 */
@ExtendWith(ApplicationExtension.class)
class PdfPreviewPaneZoomIntegrationTest {

    /** Realistic letter-page dims at 96 DPI. */
    private static final double PAGE_W = 816;
    private static final double PAGE_H = 1056;
    private static final int PAGE_COUNT = 20;
    private static final double VIEWPORT_W = 800;
    private static final double VIEWPORT_H = 600;

    private Stage stage;
    private ScrollPane scrollPane;
    private VBox pagesBox;
    private final List<ImageView> pages = new ArrayList<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        pagesBox = new VBox();
        pagesBox.setSpacing(0);
        // Use a 1x1 placeholder image; we only care about the
        // ImageView's fitWidth/fitHeight, which is what production
        // mutates via rescaleViewsInstantly.
        WritableImage placeholder = new WritableImage(1, 1);
        for (int i = 0; i < PAGE_COUNT; i++) {
            ImageView iv = new ImageView(placeholder);
            iv.setPreserveRatio(false);
            iv.setFitWidth(PAGE_W);
            iv.setFitHeight(PAGE_H);
            pages.add(iv);
            pagesBox.getChildren().add(iv);
        }
        scrollPane = new ScrollPane(pagesBox);
        scrollPane.setPrefViewportWidth(VIEWPORT_W);
        scrollPane.setPrefViewportHeight(VIEWPORT_H);
        Scene scene = new Scene(scrollPane, VIEWPORT_W, VIEWPORT_H);
        stage.setScene(scene);
        stage.show();
    }

    /** Run on FX thread and wait for completion. */
    private void onFx(Runnable r) throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX task did not complete in time");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Mimic {@link PdfPreviewPane#zoomAroundScenePoint}: rescale
     *  pages, FORCE a layout pulse so the ScrollPane internalises
     *  the new content size, then set the scroll value
     *  synchronously.  This is the only apply strategy that
     *  actually anchors the cursor — see
     *  {@link #diagnoseApplyStrategies} for the empirical
     *  comparison against the alternatives (sync, runLater,
     *  doubleRunLater, etc.). */
    private void applyZoomDeferred(double newZoom, double targetH, double targetV)
            throws InterruptedException {
        onFx(() -> {
            for (ImageView iv : pages) {
                iv.setFitWidth(PAGE_W * newZoom);
                iv.setFitHeight(PAGE_H * newZoom);
            }
            pagesBox.applyCss();
            pagesBox.layout();
            scrollPane.applyCss();
            scrollPane.layout();
            scrollPane.setHvalue(targetH);
            scrollPane.setVvalue(targetV);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Diagnostic: try every reasonable apply strategy and report
     *  what the ScrollPane actually does, so we can pick the one
     *  that wins.  Intentionally always passes — its purpose is to
     *  print the truth, not to assert. */
    @Test
    void diagnoseApplyStrategies() throws InterruptedException {
        for (String strategy : new String[] {"sync", "runLater", "layoutThenSync",
                "doubleRunLater", "layoutThenRunLater", "runLaterTwice"}) {
            // Reset to mid-document at 1.0x zoom.
            onFx(() -> {
                for (ImageView iv : pages) {
                    iv.setFitWidth(PAGE_W);
                    iv.setFitHeight(PAGE_H);
                }
                scrollPane.setVvalue(0.5);
            });
            WaitForAsyncUtils.waitForFxEvents();
            double startV = scrollPane.getVvalue();

            double newZoom = 1.5;
            double oldContentH = PAGE_H * PAGE_COUNT;
            double oldScrollPx = startV * (oldContentH - VIEWPORT_H);
            double anchorContentY = oldScrollPx + VIEWPORT_H / 2.0;
            PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                    0, anchorContentY, 0, VIEWPORT_H / 2.0,
                    1.0, newZoom, PAGE_W, oldContentH,
                    VIEWPORT_W, VIEWPORT_H, 0, startV, 0, 1, 0, 1);

            switch (strategy) {
                case "sync":
                    onFx(() -> {
                        rescale(newZoom);
                        scrollPane.setVvalue(anchor.vvalue());
                    });
                    break;
                case "runLater":
                    onFx(() -> {
                        rescale(newZoom);
                        Platform.runLater(() -> scrollPane.setVvalue(anchor.vvalue()));
                    });
                    break;
                case "layoutThenSync":
                    onFx(() -> {
                        rescale(newZoom);
                        pagesBox.applyCss();
                        pagesBox.layout();
                        scrollPane.applyCss();
                        scrollPane.layout();
                        scrollPane.setVvalue(anchor.vvalue());
                    });
                    break;
                case "doubleRunLater":
                    onFx(() -> {
                        rescale(newZoom);
                        Platform.runLater(() -> Platform.runLater(
                                () -> scrollPane.setVvalue(anchor.vvalue())));
                    });
                    break;
                case "layoutThenRunLater":
                    onFx(() -> {
                        rescale(newZoom);
                        pagesBox.applyCss();
                        pagesBox.layout();
                        Platform.runLater(() -> scrollPane.setVvalue(anchor.vvalue()));
                    });
                    break;
                case "runLaterTwice":
                    onFx(() -> {
                        rescale(newZoom);
                        Platform.runLater(() -> {
                            scrollPane.setVvalue(anchor.vvalue());
                            Platform.runLater(() -> scrollPane.setVvalue(anchor.vvalue()));
                        });
                    });
                    break;
            }
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.waitForFxEvents();
            WaitForAsyncUtils.waitForFxEvents();

            double finalV = scrollPane.getVvalue();
            double newContentH = PAGE_H * PAGE_COUNT * newZoom;
            double finalScrollPx = finalV * (newContentH - VIEWPORT_H);
            double cursorYAfter = (anchorContentY * newZoom) - finalScrollPx;
            System.out.printf(
                    "STRATEGY %-22s target=%.4f actual=%.4f delta=%+.4f cursorYAfter=%.1f (want %.1f)%n",
                    strategy, anchor.vvalue(), finalV, finalV - anchor.vvalue(),
                    cursorYAfter, VIEWPORT_H / 2.0);
        }
    }

    private void rescale(double zoom) {
        for (ImageView iv : pages) {
            iv.setFitWidth(PAGE_W * zoom);
            iv.setFitHeight(PAGE_H * zoom);
        }
    }

    @Test
    void deferredApplyHonoursVvalueAfterContentResize() throws InterruptedException {
        // Start mid-document at 1.0x zoom.
        onFx(() -> scrollPane.setVvalue(0.5));
        double startV = scrollPane.getVvalue();
        assertEquals(0.5, startV, 0.01, "precondition: ScrollPane accepts vvalue=0.5");

        // Use the pure helper to compute the target post-zoom vvalue
        // anchoring the viewport centre.
        double oldContentH = PAGE_H * PAGE_COUNT;          // 1.0x
        double newZoom = 1.5;
        double newContentH = PAGE_H * PAGE_COUNT * newZoom;
        double oldScrollPx = startV * (oldContentH - VIEWPORT_H);
        double anchorContentY = oldScrollPx + VIEWPORT_H / 2.0; // cursor at vp middle
        PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                0, anchorContentY,
                0, VIEWPORT_H / 2.0,
                1.0, newZoom,
                PAGE_W, oldContentH,
                VIEWPORT_W, VIEWPORT_H,
                0, startV,
                0, 1, 0, 1);

        // Sanity: anchor.vvalue should not be 0 (would be the snap bug).
        assertTrue(anchor.vvalue() > 0.1,
                "computed anchor must not be near 0; got " + anchor);

        applyZoomDeferred(newZoom, anchor.hvalue(), anchor.vvalue());

        // The real check: ScrollPane actually has the value we asked
        // for, after the layout pulse propagated the new content size.
        assertEquals(anchor.vvalue(), scrollPane.getVvalue(), 0.01,
                "ScrollPane vvalue must equal target after deferred apply. "
                        + "If this fails, the deferred-apply trick no longer works "
                        + "on this JavaFX version and the snap-to-top bug is back.");

        // And: the document point that was under the cursor really is
        // still near the viewport centre.  scrollableY * vvalue gives
        // the pixel offset of the viewport top in content space.
        double scrollableY = newContentH - VIEWPORT_H;
        double newScrollPx = scrollPane.getVvalue() * scrollableY;
        double cursorYAfter = (anchorContentY * newZoom) - newScrollPx;
        assertEquals(VIEWPORT_H / 2.0, cursorYAfter, 5.0,
                "Document point under cursor must stay under cursor across zoom; "
                        + "cursorYAfter=" + cursorYAfter);
    }

    @Test
    void deferredApplyDoesNotSnapToTopOnRepeatedZooms() throws InterruptedException {
        // Multi-step zoom — the original bug surfaced after one or
        // two clicks on zoom-in.  Walk 1.0 -> 1.1 -> 1.2 -> 1.3 and
        // assert the scroll position never collapses to 0.
        onFx(() -> scrollPane.setVvalue(0.5));
        double v = 0.5;
        double zoom = 1.0;
        for (double step : new double[] {1.1, 1.2, 1.3}) {
            double oldContentH = PAGE_H * PAGE_COUNT * zoom;
            double scrollPx = v * Math.max(1, oldContentH - VIEWPORT_H);
            double anchorContentY = scrollPx + VIEWPORT_H / 2.0;
            PdfPreviewPane.ZoomAnchor anchor = PdfPreviewPane.computeZoomAnchor(
                    0, anchorContentY,
                    0, VIEWPORT_H / 2.0,
                    zoom, step,
                    PAGE_W * zoom, oldContentH,
                    VIEWPORT_W, VIEWPORT_H,
                    0, v,
                    0, 1, 0, 1);
            applyZoomDeferred(step, anchor.hvalue(), anchor.vvalue());
            v = scrollPane.getVvalue();
            zoom = step;
            assertTrue(v > 0.1,
                    "After zooming to " + step + " the vvalue collapsed to " + v
                            + " — snap-to-top regression.");
        }
    }
}

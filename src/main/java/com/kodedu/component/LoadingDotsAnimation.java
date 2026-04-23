package com.kodedu.component;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counted three-dot pulse indicator for the PDF preview pane.
 *
 * <p>JavaFX equivalent of the {@code .dot-pulse} animation in
 * {@code conf/public/css/three-dots.css}.  Three small circles
 * pulse in sequence on a 1.5s cycle while at least one caller is
 * holding the indicator.  Multiple concurrent reasons can hold the
 * indicator (startup backend warm-up + an in-flight render +
 * Save\u2192PDF, etc.); the dots remain visible until every reason
 * has released, which avoids the race where one caller's release
 * clobbers another caller's still-pending visual.
 *
 * <p>The class is intentionally a small, focused unit so its
 * concurrency contract (ref counting, idempotent release,
 * thread-safe holds, FX-thread-only timeline mutation) can be
 * pinned by {@code LoadingDotsAnimationTest} without spinning up
 * the full {@link PdfPreviewPane} Spring bean.
 *
 * <p>All public state-changing methods are safe to call from any
 * thread; visibility / timeline mutation is marshalled to the FX
 * thread via {@link Platform#runLater}.
 */
final class LoadingDotsAnimation {

    /** Hex matches {@code three-dots.css}. */
    private static final Color DOT_COLOR = Color.web("#9880ff");
    private static final double DOT_RADIUS = 5;
    private static final double DOT_SPACING = 6;

    private final HBox node = new HBox(DOT_SPACING);
    private final Timeline timeline = new Timeline();
    private final AtomicInteger refCount = new AtomicInteger(0);

    LoadingDotsAnimation() {
        Circle d1 = new Circle(DOT_RADIUS, DOT_COLOR);
        Circle d2 = new Circle(DOT_RADIUS, DOT_COLOR);
        Circle d3 = new Circle(DOT_RADIUS, DOT_COLOR);
        node.getChildren().setAll(d1, d2, d3);
        node.setAlignment(Pos.CENTER);
        node.setVisible(false);
        node.setManaged(false);

        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(d1.opacityProperty(), 0.2),
                        new KeyValue(d2.opacityProperty(), 0.2),
                        new KeyValue(d3.opacityProperty(), 0.2)),
                new KeyFrame(Duration.millis(250),
                        new KeyValue(d1.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(500),
                        new KeyValue(d1.opacityProperty(), 0.2),
                        new KeyValue(d2.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(750),
                        new KeyValue(d2.opacityProperty(), 0.2),
                        new KeyValue(d3.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(1500),
                        new KeyValue(d3.opacityProperty(), 0.2)));
    }

    /** The toolbar-mounted control. */
    HBox node() {
        return node;
    }

    /** Visible only for tests; production code observes {@link #node()}. */
    Timeline timelineForTest() {
        return timeline;
    }

    /** Visible only for tests. */
    int refCountForTest() {
        return refCount.get();
    }

    /**
     * Acquire a hold on the indicator.  Returns an
     * {@link AutoCloseable} that releases the hold exactly once when
     * closed; subsequent {@code close()} calls are no-ops, which
     * means callers can use try-with-resources without worrying
     * about double-release leaking the indicator into the
     * permanently-on state.
     */
    AutoCloseable hold() {
        increment();
        AtomicBoolean released = new AtomicBoolean(false);
        return () -> {
            if (released.compareAndSet(false, true)) {
                decrement();
            }
        };
    }

    /** Increment the ref count and ensure the indicator is visible. */
    private void increment() {
        refCount.incrementAndGet();
        scheduleVisibilitySync();
    }

    /** Decrement the ref count (clamped to 0) and hide if reached 0. */
    private void decrement() {
        while (true) {
            int prev = refCount.get();
            if (prev <= 0) {
                // Already at zero; do not go negative.  Refresh
                // visibility to be defensive but don't double-stop.
                scheduleVisibilitySync();
                return;
            }
            if (refCount.compareAndSet(prev, prev - 1)) {
                break;
            }
        }
        scheduleVisibilitySync();
    }

    /** Manual hold acquisition for paired-call sites that don't fit
     *  the {@link #hold()} try-with-resources pattern (e.g. when the
     *  acquire and release happen on different control-flow branches).
     *  Callers MUST pair every {@code incrementHold()} with exactly
     *  one {@code decrementHold()}; over-decrement is clamped at 0. */
    void incrementHold() {
        increment();
    }

    /** Pair to {@link #incrementHold()}. */
    void decrementHold() {
        decrement();
    }

    /**
     * Schedule a visibility sync on the FX thread.  Reads {@link #refCount}
     * at execution time rather than capturing a {@code shouldShow}
     * argument: with many concurrent acquirers / releasers, the order
     * in which threads enqueue {@code Platform.runLater} tasks does NOT
     * always match the order in which they updated the atomic counter
     * (the increment-then-runLater sequence is not atomic).  Reading
     * the counter inside the runLater body guarantees the FX-thread
     * visibility always reflects the current state, not a stale
     * snapshot from whichever thread happened to enqueue last.
     */
    private void scheduleVisibilitySync() {
        Platform.runLater(() -> {
            boolean shouldShow = refCount.get() > 0;
            if (node.isVisible() == shouldShow) {
                return;
            }
            node.setVisible(shouldShow);
            node.setManaged(shouldShow);
            if (shouldShow) {
                timeline.playFromStart();
            } else {
                timeline.stop();
            }
        });
    }
}

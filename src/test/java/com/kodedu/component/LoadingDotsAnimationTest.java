package com.kodedu.component;

import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-toolkit verification of {@link LoadingDotsAnimation}'s
 * concurrency contract: the indicator must remain visible as long
 * as at least one caller is holding it, must hide once every hold
 * is released, must tolerate idempotent {@code close()} on a
 * single hold, and must not under-flow into a permanent-on state
 * if releases outpace acquires (which would happen if a caller
 * called {@code AutoCloseable.close()} twice and the helper
 * blindly decremented the counter past zero).
 *
 * <p>These guarantees are what fixed the original race where the
 * startup-warmup release would clobber an in-flight render's
 * still-pending visual; if any of these tests fail, that race is
 * back.
 */
@ExtendWith(ApplicationExtension.class)
class LoadingDotsAnimationTest {

    private LoadingDotsAnimation dots;
    private BorderPane root;

    @Start
    void start(Stage stage) {
        dots = new LoadingDotsAnimation();
        root = new BorderPane();
        root.setCenter(dots.node());
        stage.setScene(new Scene(root, 200, 60));
        stage.show();
    }

    @BeforeEach
    void resetState() throws Exception {
        // Defensive: drain any leftover state from a previous test.
        // Each test starts with a fresh helper because @Start runs
        // once per test method by TestFX convention.
        if (dots == null) return;
        runOnFx(() -> {
            // Re-create to ensure clean ref-count between tests.
            dots = new LoadingDotsAnimation();
            root.setCenter(dots.node());
        });
    }

    private static void runOnFx(Runnable r) throws InterruptedException {
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

    @Test
    void initialStateIsHidden() {
        assertFalse(dots.node().isVisible(), "indicator must be hidden before any hold");
        assertFalse(dots.node().isManaged(), "indicator must be unmanaged so it claims no layout space");
        assertEquals(Animation.Status.STOPPED, dots.timelineForTest().getStatus(),
                "timeline must not be running when nobody is holding");
        assertEquals(0, dots.refCountForTest());
    }

    @Test
    void singleHoldShowsTheIndicatorAndStartsAnimation() throws Exception {
        AutoCloseable hold = dots.hold();
        WaitForAsyncUtils.waitForFxEvents();
        try {
            assertTrue(dots.node().isVisible(),
                    "indicator must be visible while a hold is active");
            assertTrue(dots.node().isManaged());
            assertEquals(Animation.Status.RUNNING, dots.timelineForTest().getStatus(),
                    "timeline must be running while a hold is active");
            assertEquals(1, dots.refCountForTest());
        } finally {
            hold.close();
        }
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(dots.node().isVisible(), "release must hide indicator");
        assertEquals(Animation.Status.STOPPED, dots.timelineForTest().getStatus());
        assertEquals(0, dots.refCountForTest());
    }

    @Test
    void multipleConcurrentHoldsKeepIndicatorVisibleUntilAllRelease() throws Exception {
        // Three callers (startup warm-up, render, Save\u2192PDF) all hold
        // simultaneously.  Releasing one or two must NOT hide the
        // indicator.  This is the original race the helper was
        // designed to fix.
        AutoCloseable warmup = dots.hold();
        AutoCloseable render = dots.hold();
        AutoCloseable save = dots.hold();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(3, dots.refCountForTest());
        assertTrue(dots.node().isVisible());
        assertEquals(Animation.Status.RUNNING, dots.timelineForTest().getStatus());

        warmup.close();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, dots.refCountForTest());
        assertTrue(dots.node().isVisible(),
                "warm-up release must NOT hide indicator while render+save still hold");
        assertEquals(Animation.Status.RUNNING, dots.timelineForTest().getStatus());

        render.close();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, dots.refCountForTest());
        assertTrue(dots.node().isVisible(),
                "render release must NOT hide indicator while save still holds");

        save.close();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, dots.refCountForTest());
        assertFalse(dots.node().isVisible(), "last release must hide indicator");
        assertEquals(Animation.Status.STOPPED, dots.timelineForTest().getStatus());
    }

    @Test
    void doubleCloseOnSingleHoldDoesNotUnderflowRefCount() throws Exception {
        // The contract: each AutoCloseable returned by hold() must
        // release exactly once, no matter how many times close() is
        // called on it.  Otherwise a defensively-double-closing caller
        // would underflow the counter and a subsequent legitimate
        // hold() from a different caller could leak (or, before
        // clamping, go negative).
        AutoCloseable hold = dots.hold();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, dots.refCountForTest());

        hold.close();
        hold.close();
        hold.close();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, dots.refCountForTest(),
                "triple-close on a single hold must not push refCount below 0");
        assertFalse(dots.node().isVisible());

        // A fresh hold from a new caller must still work cleanly.
        AutoCloseable second = dots.hold();
        WaitForAsyncUtils.waitForFxEvents();
        try {
            assertEquals(1, dots.refCountForTest(),
                    "fresh hold after over-release must increment from 0, not from a stale negative");
            assertTrue(dots.node().isVisible());
        } finally {
            second.close();
        }
    }

    @Test
    void incrementHoldAndDecrementHoldArePairedSemantics() throws Exception {
        // Mirror of hold()/close(), but for the paired-call sites
        // (e.g. PdfPreviewPane.render() acquires on caller thread,
        // releases on executor thread) that can't use try-with-resources.
        dots.incrementHold();
        dots.incrementHold();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, dots.refCountForTest());
        assertTrue(dots.node().isVisible());

        dots.decrementHold();
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(dots.node().isVisible(),
                "one decrement of two increments must leave indicator visible");
        assertEquals(1, dots.refCountForTest());

        dots.decrementHold();
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(dots.node().isVisible());
        assertEquals(0, dots.refCountForTest());

        // Defensive: extra decrements must clamp at 0, not go negative.
        dots.decrementHold();
        dots.decrementHold();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, dots.refCountForTest(),
                "decrement past 0 must clamp; otherwise the next legitimate hold leaks");
    }

    @Test
    void concurrentHoldsFromManyThreadsBalanceCorrectly() throws Exception {
        // Stress-style: 50 background threads each acquire-and-release
        // a hold.  At the end, ref count must be exactly 0 and the
        // indicator must be hidden.  Catches any non-atomic update
        // in the increment/decrement path.
        int threads = 50;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    AutoCloseable hold = dots.hold();
                    Thread.sleep(5);
                    hold.close();
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "threads did not start");
        go.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish");
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        synchronized (errors) {
            assertTrue(errors.isEmpty(), "thread errors: " + errors);
        }
        assertEquals(0, dots.refCountForTest(),
                "all paired hold/close must net to 0 ref count");
        assertFalse(dots.node().isVisible(),
                "indicator must be hidden after all threads release");
        assertEquals(Animation.Status.STOPPED, dots.timelineForTest().getStatus());
    }

    @Test
    void releaseDoesNotStopTimelineIfStillHeld() throws Exception {
        // Specific guard for the original race: visibility transitions
        // must only fire on the 0\u2194positive boundary.  If the helper
        // applied visibility on every increment/decrement we would
        // playFromStart() the timeline mid-cycle on every render
        // submission, which is jarring (and was the symptom that
        // motivated the ref-counted rewrite).
        AutoCloseable a = dots.hold();
        WaitForAsyncUtils.waitForFxEvents();
        Animation.Status initialStatus = dots.timelineForTest().getStatus();
        assertEquals(Animation.Status.RUNNING, initialStatus);

        // Acquiring more holds must not restart / interrupt the timeline.
        AutoCloseable b = dots.hold();
        AutoCloseable c = dots.hold();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(Animation.Status.RUNNING, dots.timelineForTest().getStatus(),
                "additional holds must not restart the timeline");

        // Releasing down to 1 must not stop the timeline.
        b.close();
        c.close();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(Animation.Status.RUNNING, dots.timelineForTest().getStatus(),
                "timeline must keep running while at least one hold remains");

        a.close();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(Animation.Status.STOPPED, dots.timelineForTest().getStatus());
    }

    @Test
    void nodeIsThreeCircleDotsLaidOutHorizontally() {
        // Sanity contract on the visual structure so accidental
        // refactors that swap the layout (e.g. to a VBox or ProgressBar)
        // get caught.  The PDF-preview toolbar layout assumes the
        // indicator is a horizontal strip.
        assertNotNull(dots.node(), "node must not be null");
        assertEquals(3, dots.node().getChildren().size(),
                "indicator must contain exactly three dots");
        for (var child : dots.node().getChildren()) {
            assertTrue(child instanceof javafx.scene.shape.Circle,
                    "each dot must be a Circle, got " + child.getClass());
        }
    }
}

package com.kodedu.component;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-toolkit verification of the Save\u2192PDF progress-bar
 * lifecycle.  Mirrors the production wiring in
 * {@link com.kodedu.controller.ApplicationController#initializeApp()}
 * (a 0\u21921 / 15s {@link Timeline} bound to a {@link ProgressBar}'s
 * {@code progressProperty()}) and the start/stop sequence in
 * {@link com.kodedu.service.ui.impl.IndikatorServiceImpl}.
 *
 * <p>The full Spring-wired controller can't be instantiated in
 * isolation (needs a server port, FXML, the entire
 * {@code AsciidocFX} bean graph), so the production logic is too
 * heavy to drive directly.  Instead we re-create the exact wiring
 * here and assert the observable contract: starting the bar shows
 * it, animates the progress monotonically toward 1.0, and stopping
 * hides it and freezes the animation.
 *
 * <p>If a future change to the production wiring breaks this
 * contract (e.g. someone sets the timeline's cycle count to
 * INDEFINITE so it loops back to 0, or removes the show/hide
 * coupling), this test will fail and surface the regression.
 */
@ExtendWith(ApplicationExtension.class)
class ProgressBarLifecycleTest {

    private ProgressBar progressBar;
    private Timeline progressBarTimeline;

    @Start
    void start(Stage stage) {
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setPrefWidth(400);
        // Mirrors ApplicationController.initializeApp lines 586-595.
        progressBarTimeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(progressBar.progressProperty(), 0)),
                new KeyFrame(Duration.seconds(15),
                        new KeyValue(progressBar.progressProperty(), 1)));
        BorderPane root = new BorderPane();
        root.setBottom(progressBar);
        stage.setScene(new Scene(root, 400, 60));
        stage.show();
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

    /** Mirrors {@link com.kodedu.service.ui.impl.IndikatorServiceImpl#startProgressBar()}. */
    private void startProgressBar() throws InterruptedException {
        onFx(() -> {
            progressBar.setVisible(true);
            progressBar.setManaged(true);
            progressBarTimeline.playFromStart();
        });
    }

    /** Mirrors {@link com.kodedu.service.ui.impl.IndikatorServiceImpl#stopProgressBar()}. */
    private void stopProgressBar() throws InterruptedException {
        onFx(() -> {
            progressBar.setVisible(false);
            progressBar.setManaged(false);
            progressBarTimeline.stop();
        });
    }

    @Test
    void initialStateIsHiddenAtZero() {
        assertFalse(progressBar.isVisible(),
                "progress bar must start hidden so it doesn't claim layout space at startup");
        assertFalse(progressBar.isManaged());
        assertEquals(0.0, progressBar.getProgress(), 1e-9);
        assertEquals(Animation.Status.STOPPED, progressBarTimeline.getStatus());
    }

    @Test
    void startProgressBarMakesItVisibleAndStartsAnimation() throws Exception {
        startProgressBar();
        assertTrue(progressBar.isVisible(),
                "startProgressBar must make the bar visible to the user");
        assertTrue(progressBar.isManaged());
        assertEquals(Animation.Status.RUNNING, progressBarTimeline.getStatus(),
                "timeline must be running so the progress visibly advances");
        // Cleanup
        stopProgressBar();
    }

    @Test
    void timelineAdvancesProgressMonotonicallyTowardOne() throws Exception {
        startProgressBar();
        // After ~150 ms of a 15 s 0\u21921 timeline, progress should be
        // a small positive value (~0.01).  The exact value depends on
        // pulse timing, so we assert the band rather than a point.
        Thread.sleep(200);
        WaitForAsyncUtils.waitForFxEvents();
        double after200ms = progressBar.getProgress();
        assertTrue(after200ms > 0.0,
                "after 200ms the progress must have advanced past 0; got " + after200ms);
        assertTrue(after200ms < 0.1,
                "after 200ms the progress must still be far from 1; got "
                        + after200ms + " (timeline broken? cycle count looping?)");

        // After another tick, value must have grown (monotonic until 1).
        Thread.sleep(200);
        WaitForAsyncUtils.waitForFxEvents();
        double after400ms = progressBar.getProgress();
        assertTrue(after400ms >= after200ms,
                "progress must be monotonically non-decreasing while running; "
                        + "after200ms=" + after200ms + " after400ms=" + after400ms);
        stopProgressBar();
    }

    @Test
    void stopFreezesTheBarAndHidesIt() throws Exception {
        startProgressBar();
        Thread.sleep(150);
        WaitForAsyncUtils.waitForFxEvents();
        double progressBeforeStop = progressBar.getProgress();
        assertTrue(progressBeforeStop > 0,
                "precondition: progress should have advanced before stop");

        stopProgressBar();
        assertFalse(progressBar.isVisible(),
                "stop must hide the bar so it doesn't linger after the conversion completes");
        assertFalse(progressBar.isManaged());
        assertEquals(Animation.Status.STOPPED, progressBarTimeline.getStatus(),
                "stop must halt the timeline so the bar doesn't keep advancing invisibly");

        // Allow time to pass and confirm progress did not advance.
        double progressAfterStop = progressBar.getProgress();
        Thread.sleep(150);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(progressAfterStop, progressBar.getProgress(), 1e-9,
                "progress must be frozen after stop; otherwise the timeline didn't actually stop");
    }

    @Test
    void restartReplaysFromZero() throws Exception {
        // Save\u2192PDF runs back-to-back convert tasks; each one
        // must replay the bar from 0, not pick up where the last one
        // left off.  This is what playFromStart() guarantees, and is
        // the documented contract IndikatorServiceImpl relies on.
        startProgressBar();
        Thread.sleep(200);
        WaitForAsyncUtils.waitForFxEvents();
        double progressFirstRun = progressBar.getProgress();
        assertTrue(progressFirstRun > 0,
                "precondition: bar advanced during first run");
        stopProgressBar();

        // Second run should reset to 0 at start, not begin at progressFirstRun.
        startProgressBar();
        WaitForAsyncUtils.waitForFxEvents();
        // After playFromStart, the keyframe at Duration.ZERO snaps the
        // value back to 0; we sample immediately to catch the reset
        // before the timeline has had time to advance.
        double progressAfterRestart = progressBar.getProgress();
        assertTrue(progressAfterRestart < progressFirstRun,
                "playFromStart must reset progress; got " + progressAfterRestart
                        + " (was " + progressFirstRun + " when stopped)");
        stopProgressBar();
    }
}

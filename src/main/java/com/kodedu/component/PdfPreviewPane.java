package com.kodedu.component;

import com.kodedu.config.BrowserType;
import com.kodedu.config.EditorConfigBean;
import com.kodedu.config.PreviewConfigBean;
import com.kodedu.controller.ApplicationController;
import com.kodedu.helper.ClipboardHelper;
import com.kodedu.helper.FxHelper;
import com.kodedu.other.Current;
import com.kodedu.service.ThreadService;
import com.kodedu.service.convert.pdf.PdfRenderer;
import com.kodedu.service.preview.PreviewScope;
import com.kodedu.service.preview.PreviewSourceResolver;
import jakarta.annotation.PostConstruct;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live PDF preview pane backed by PDFBox-rasterized page images.
 *
 * <p>Sibling of {@link HtmlPane}, {@link SlidePane}, {@link LiveReloadPane} —
 * extends {@link ViewPanel} so it slots into the existing
 * {@code rightShowerHider} preview area without any FXML changes.  Selected
 * via the {@code previewBackend} setting on
 * {@link com.kodedu.config.PreviewConfigBean}; default is PDF.
 *
 * <p>The render pipeline is the same {@link PdfRenderer} used by
 * "Save → PDF", so what you see here is byte-for-byte the document the user
 * will export.  No JavaScript, no external assets — pure Java via PDFBox.
 *
 * <p>Render is serialized on a single-thread executor.  asciidoctor-pdf is
 * single-threaded JRuby anyway, so explicit serialization gives trivial
 * cancellation (drop the queued task; let an in-flight task finish and the
 * next one's output silently overwrites the temp PDF).
 */
@Component
public class PdfPreviewPane extends ViewPanel {

    private static final Logger logger = LoggerFactory.getLogger(PdfPreviewPane.class);

    /** PDFBox render DPI at zoom = 1.0.  Approx screen DPI on most monitors. */
    private static final float BASE_DPI = 96f;

    private final PreviewConfigBean previewConfigBean;
    private final PdfRenderer pdfRenderer;

    /** Random suffix to keep the temp PDF and render thread name unique
     *  across concurrently-running AsciidocFX processes. */
    private final String instanceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final Path tmpPdf;
    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pdf-preview-" + instanceId);
                t.setDaemon(true);
                return t;
            });
    private final AtomicReference<Future<?>> inFlight = new AtomicReference<>();
    private volatile int lastRenderHash;

    /**
     * Cache of page images at the DPI they were rasterised at, keyed by
     * page index.  Zoom changes repaint by rescaling these images via
     * {@link ImageView#setFitWidth(double)} on the FX thread (microsecond
     * cost) and only kick off a heavy re-rasterisation if the new zoom
     * exceeds the cached DPI's natural pixel-per-point ratio (i.e. we
     * would visibly blur).  A debounced re-rasterise then runs on the
     * render executor so dragging the slider doesn't queue dozens of
     * PDFBox loads.
     */
    private final List<Image> cachedImages = new ArrayList<>();
    /** DPI the {@link #cachedImages} were rendered at. */
    private volatile float cachedImagesDpi = BASE_DPI;
    /** Page widths in PDF points, used to scale ImageViews instantly on zoom. */
    private final List<Double> cachedPagePtWidths = new ArrayList<>();
    /** Debounce timer for the heavy re-rasterise. Run on FX thread. */
    private javafx.animation.PauseTransition zoomDebounce;
    /** Tracks whether a re-rasterise is currently in flight on the executor. */
    private final java.util.concurrent.atomic.AtomicBoolean reraserizing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * User's explicit master choice for an ambiguous chapter.  Keyed by
     * the chapter's normalised absolute path so we only show the
     * disambiguation dialog once per chapter per session.  Cleared if the
     * user picks "Always ask" (not yet wired) or clears the project.
     */
    private final java.util.Map<Path, Path> chapterMasterChoice = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Currently-resolved master document for the active tab.  Observable
     * so the file-tree cell factory can paint a "MASTER" badge on the
     * matching tree row.  Updated on the FX thread after each render
     * settles on a master.
     */
    private final javafx.beans.property.ObjectProperty<Path> currentMaster =
            new javafx.beans.property.SimpleObjectProperty<>();

    /** Re-entrancy guard so concurrent renders don't pop multiple dialogs. */
    private final java.util.concurrent.atomic.AtomicBoolean dialogOpen =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final VBox pagesBox = new VBox(8);
    private final ScrollPane scrollPane = new ScrollPane(pagesBox);
    private final Slider zoomSlider = new Slider(0.5, 2.0, 1.0);

    /** JavaFX equivalent of the {@code .dot-pulse} animation in
     *  {@code conf/public/css/three-dots.css}, shown while a render is
     *  in flight.  Three small circles pulsing in sequence. */
    private final HBox loadingDots = new HBox(6);
    private final Timeline loadingTimeline = new Timeline();

    @Autowired
    public PdfPreviewPane(ThreadService threadService,
                          ApplicationController controller,
                          Current current,
                          EditorConfigBean editorConfigBean,
                          ClipboardHelper clipboardHelper,
                          PreviewConfigBean previewConfigBean,
                          PdfRenderer pdfRenderer) {
        super(threadService, controller, current, editorConfigBean, clipboardHelper);
        this.previewConfigBean = previewConfigBean;
        this.pdfRenderer = pdfRenderer;
        try {
            this.tmpPdf = Files.createTempFile("afx-preview-" + instanceId + "-", ".pdf");
            this.tmpPdf.toFile().deleteOnExit();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot allocate preview PDF temp file", e);
        }
    }

    /**
     * Override the parent's WebView-bootstrap.  We don't use a WebView — the
     * pane is a {@link BorderPane} (status bar + zoom + scrollable pages).
     */
    @PostConstruct
    @Override
    public void afterViewInit() {
        threadService.runActionLater(() -> {
            BorderPane root = new BorderPane();
            FxHelper.fitToParent(root);
            getChildren().add(root);

            pagesBox.setAlignment(Pos.TOP_CENTER);
            pagesBox.setPadding(new Insets(8));
            pagesBox.setFillWidth(false);
            scrollPane.setFitToWidth(true);
            scrollPane.setPannable(true);

            // Ctrl + scroll wheel zooms the preview, swallowing the event so
            // the page doesn't also scroll. Step matches the +/- buttons.
            scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, ev -> {
                if (!ev.isControlDown()) {
                    return;
                }
                double step = zoomSlider.getBlockIncrement();
                double delta = ev.getDeltaY() > 0 ? step : -step;
                double next = Math.max(zoomSlider.getMin(),
                        Math.min(zoomSlider.getMax(), zoomSlider.getValue() + delta));
                if (Math.abs(next - zoomSlider.getValue()) > 0.0001) {
                    zoomSlider.setValue(next);
                }
                ev.consume();
            });

            zoomSlider.setPrefWidth(140);
            zoomSlider.setShowTickMarks(true);
            zoomSlider.setBlockIncrement(0.1);
            // Debounce the heavy PDFBox re-rasterise so dragging the slider
            // doesn't queue a load+render per tick.  The lightweight
            // ImageView rescale (instant) runs on every change for
            // responsive feedback.
            zoomDebounce = new javafx.animation.PauseTransition(Duration.millis(220));
            zoomDebounce.setOnFinished(e -> rerasterizeIfQualityWouldImprove());
            zoomSlider.valueProperty().addListener((obs, ov, nv) -> {
                if (Math.abs(nv.doubleValue() - ov.doubleValue()) > 0.001) {
                    rescaleViewsInstantly(nv.doubleValue());
                    zoomDebounce.playFromStart();
                }
            });

            buildLoadingDots();

            Button refreshButton = new Button("Refresh");
            refreshButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Re-render the PDF preview now (F5)"));
            refreshButton.setOnAction(e -> render());

            // Zoom group: [\u2212] [---slider---] [+]
            // Step is a single tick of the slider's blockIncrement, clamped
            // to its [min, max] range. Buttons make zoom usable without a
            // mouse wheel / drag.
            Button zoomOutButton = new Button("\u2212");
            zoomOutButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Zoom out (Ctrl + scroll down)"));
            zoomOutButton.setOnAction(e -> zoomSlider.setValue(
                    Math.max(zoomSlider.getMin(),
                            zoomSlider.getValue() - zoomSlider.getBlockIncrement())));
            Button zoomInButton = new Button("+");
            zoomInButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Zoom in (Ctrl + scroll up)"));
            zoomInButton.setOnAction(e -> zoomSlider.setValue(
                    Math.min(zoomSlider.getMax(),
                            zoomSlider.getValue() + zoomSlider.getBlockIncrement())));
            HBox zoomGroup = new HBox(4, zoomOutButton, zoomSlider, zoomInButton);
            zoomGroup.setAlignment(Pos.CENTER_RIGHT);

            // Three-region toolbar via BorderPane:
            //   left   = Refresh button
            //   center = loading dots (centered above the page area)
            //   right  = zoom controls

            BorderPane toolbar = new BorderPane();
            toolbar.setLeft(refreshButton);
            toolbar.setCenter(loadingDots);
            toolbar.setRight(zoomGroup);
            BorderPane.setAlignment(refreshButton, Pos.CENTER_LEFT);
            BorderPane.setAlignment(loadingDots, Pos.CENTER);
            BorderPane.setAlignment(zoomGroup, Pos.CENTER_RIGHT);
            toolbar.setPadding(new Insets(4, 8, 4, 8));

            root.setTop(toolbar);
            root.setCenter(scrollPane);
        });
    }

    /**
     * Build the three-dot pulse animation (JavaFX equivalent of
     * {@code .dot-pulse} from {@code conf/public/css/three-dots.css}).
     * Hidden by default; toggled via {@link #setLoading(boolean)}.
     */
    private void buildLoadingDots() {
        Color dotColor = Color.web("#9880ff"); // matches three-dots.css
        Circle d1 = new Circle(5, dotColor);
        Circle d2 = new Circle(5, dotColor);
        Circle d3 = new Circle(5, dotColor);
        loadingDots.getChildren().setAll(d1, d2, d3);
        loadingDots.setAlignment(Pos.CENTER);
        loadingDots.setVisible(false);
        loadingDots.setManaged(false);

        // Three offset opacity pulses, ~1.5s cycle (mirrors the CSS).
        loadingTimeline.setCycleCount(Animation.INDEFINITE);
        loadingTimeline.getKeyFrames().setAll(
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
                        new KeyValue(d3.opacityProperty(), 0.2))
        );
    }

    private void setLoading(boolean loading) {
        Platform.runLater(() -> {
            loadingDots.setVisible(loading);
            loadingDots.setManaged(loading);
            if (loading) {
                loadingTimeline.playFromStart();
            } else {
                loadingTimeline.stop();
            }
        });
    }

    /**
     * Trigger a render of the currently active document.  Safe to call from
     * any thread; the actual render runs on the dedicated executor.
     *
     * <p>If a render is queued but not yet started, it is dropped.  If a
     * render is already running, it is allowed to complete and the next
     * render's output silently overwrites the temp PDF.
     */
    public void render() {
        Future<?> previous = inFlight.getAndSet(null);
        if (previous != null) {
            previous.cancel(false);
        }
        logger.debug("PDF preview render queued");
        setLoading(true);
        Future<?> next = renderExecutor.submit(() -> {
            try {
                renderNow();
            } finally {
                setLoading(false);
            }
        });
        inFlight.set(next);
    }

    private void renderNow() {
        try {
            com.kodedu.component.MyTab tab = current.currentTab();
            if (tab == null || tab.getPath() == null) {
                return;
            }
            // Editor JS may not have booted yet at startup (Ace bootstrap
            // races the first render trigger). Defer and self-rearm via
            // the readiness property; the listener removes itself on the
            // first true-transition.
            com.kodedu.component.EditorPane editorPane = tab.getEditorPane();
            if (editorPane == null || !editorPane.getReady()) {
                if (editorPane != null) {
                    final javafx.beans.property.BooleanProperty ready = editorPane.readyProperty();
                    final javafx.beans.value.ChangeListener<Boolean>[] holder =
                            new javafx.beans.value.ChangeListener[1];
                    holder[0] = (obs, was, isNow) -> {
                        if (Boolean.TRUE.equals(isNow)) {
                            ready.removeListener(holder[0]);
                            render();
                        }
                    };
                    ready.addListener(holder[0]);
                }
                return;
            }
            String asciidoc = current.currentEditorValue();
            if (asciidoc == null) {
                return;
            }
            Path masterAdoc = resolveMasterAdoc();
            if (masterAdoc == null) {
                return;
            }
            // Publish the resolved master so the file-tree cell factory
            // can paint a "MASTER" badge on the matching row.
            Platform.runLater(() -> currentMaster.set(masterAdoc));
            Path activeFile = tab.getPath();
            PreviewScope scope = previewConfigBean.getPdfPreviewScope();

            PreviewSourceResolver.Resolved r =
                    PreviewSourceResolver.resolve(scope, masterAdoc, activeFile, asciidoc);
            logger.info("PDF preview render start: scope={} master={} active={}",
                    r.scopeUsed(), masterAdoc, activeFile);
            if (r.notice() != null) {
                logger.info("PDF preview: {}", r.notice());
            }

            int hash = (r.scopeUsed().name() + "|" + r.source()).hashCode();
            if (hash == lastRenderHash && Files.size(tmpPdf) > 0) {
                logger.debug("PDF preview unchanged since last render; reusing {}", tmpPdf);
                return;
            }

            long t0 = System.currentTimeMillis();
            pdfRenderer.renderTo(r.source(), r.baseDir(), tmpPdf);
            lastRenderHash = hash;
            logger.debug("PDF preview rendered ({}, {} ms)",
                    r.scopeUsed(), System.currentTimeMillis() - t0);

            rasterizeAndShow();

            // Asciidoctor-pdf can take seconds; if the user kept typing
            // during the render, fire one more so the visible PDF
            // converges on the current editor buffer. lastRenderHash
            // above prevents loops when nothing actually changed.
            if (current.currentTab() == tab) {
                String latest = current.currentEditorValue();
                if (latest != null && !latest.equals(asciidoc)) {
                    logger.debug("Editor changed during render; re-rendering to catch up");
                    threadService.schedule(this::render, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            logger.warn("PDF preview render failed (keeping previous pages visible)", e);
        }
    }

    private Path resolveMasterAdoc() {
        if (current.currentTab() == null) {
            return null;
        }
        Path active = current.currentTab().getPath();
        if (active == null) {
            return null;
        }
        Path projectDir = findProjectDir(current.currentTab().getParentOrWorkdir());
        if (projectDir == null) {
            return active;
        }
        Path candidate = pickMasterIn(projectDir, active);
        return candidate != null ? candidate : active;
    }

    /**
     * Walk up from {@code start} looking for {@code .asciidoctorconfig}
     * (the project marker). Returns null if no marker is found within
     * 10 levels.
     */
    private static Path findProjectDir(Path start) {
        Path dir = start;
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".asciidoctorconfig"))
                    || Files.exists(dir.resolve(".asciidoctorconfig.adoc"))) {
                return dir;
            }
        }
        return null;
    }

    private Path pickMasterIn(Path projectDir, Path active) {
        // Always BFS the include graph from every top-level .adoc.  No
        // location-based shortcuts: a top-level .adoc can itself be a
        // child of a "book of books" master, and a chapter buried in a
        // subfolder might not have any parent at all.
        //
        //   1+ referrers (one)        -> use it (no UI).
        //   2+ referrers              -> per-chapter cache, else dialog.
        //   0 referrers (orphan)      -> render the active file itself.
        Path activeAbs = active.toAbsolutePath().normalize();
        java.util.List<Path> referrers =
                com.kodedu.service.preview.MasterDocResolver.findReferrers(projectDir, activeAbs);

        if (referrers.size() == 1) {
            return referrers.get(0);
        }

        if (referrers.size() > 1) {
            Path cached = chapterMasterChoice.get(activeAbs);
            if (cached != null && referrers.contains(cached)) {
                return cached;
            }
            Path chosen = promptForMaster(activeAbs, referrers);
            if (chosen != null) {
                chapterMasterChoice.put(activeAbs, chosen);
                return chosen;
            }
            // User cancelled — render against the first referrer rather
            // than failing.
            return referrers.get(0);
        }

        // No top-level .adoc includes this chapter (orphan, attribute
        // references we can't resolve, or a standalone file).  Render
        // the active file as its own master.
        return active;
    }

    /**
     * Show a modal disambiguation dialog listing every top-level {@code .adoc}
     * that includes the active chapter.  Blocks the calling (render-executor)
     * thread until the user picks or cancels.
     *
     * <p>Uses {@link Platform#runLater(Runnable)} + a latch instead of
     * {@code FutureTask} on the FX thread because we need to keep the
     * render serialized — running this from the JavaFX-application thread
     * itself would deadlock.
     */
    private Path promptForMaster(Path chapter, java.util.List<Path> candidates) {
        if (!dialogOpen.compareAndSet(false, true)) {
            // Another render is already showing the dialog; just wait it
            // out by returning the first candidate. The user's eventual
            // choice will land in the cache and apply to the next render.
            return candidates.get(0);
        }
        try {
            final java.util.concurrent.atomic.AtomicReference<Path> result =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    result.set(showMasterDialog(chapter, candidates));
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            return result.get();
        } finally {
            dialogOpen.set(false);
        }
    }

    private Path showMasterDialog(Path chapter, java.util.List<Path> candidates) {
        javafx.scene.control.Dialog<Path> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Choose master document");
        dialog.setHeaderText("\"" + chapter.getFileName() + "\" is included by more than one book.");

        Label hint = new Label(
                "Pick which top-level .adoc to render this chapter in.\n" +
                "The choice is remembered for this chapter until the app restarts.");
        hint.setWrapText(true);

        javafx.scene.control.ListView<Path> listView = new javafx.scene.control.ListView<>();
        listView.getItems().setAll(candidates);
        listView.setCellFactory(lv -> new javafx.scene.control.ListCell<Path>() {
            @Override protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFileName().toString());
            }
        });
        listView.getSelectionModel().select(0);
        listView.setPrefHeight(Math.min(220, 28 * candidates.size() + 28));

        VBox box = new VBox(8, hint, listView);
        box.setPadding(new Insets(10));
        box.setPrefWidth(380);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().setAll(
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(bt ->
                bt == javafx.scene.control.ButtonType.OK
                        ? listView.getSelectionModel().getSelectedItem()
                        : null);
        return dialog.showAndWait().orElse(null);
    }

    /**
     * Read-only observable of the currently-resolved master.  Bound to by
     * the file-tree cell factory in {@link com.kodedu.controller.ApplicationController}
     * so the master file gets a coloured badge in the navigation tree.
     */
    public javafx.beans.property.ReadOnlyObjectProperty<Path> currentMasterProperty() {
        return currentMaster;
    }

    /**
     * Resolve the master document for {@code activeFile} off the FX thread
     * and publish it to {@link #currentMasterProperty()} — without
     * triggering a PDF render.  Called when the user switches tabs so the
     * MASTER badge moves immediately and any disambiguation dialog fires
     * at click-time rather than mid-typing.
     *
     * <p>If the active file lives in a project with multiple parents, this
     * may pop the disambiguation dialog (handled on the FX thread by
     * {@link #promptForMaster}).
     */
    public void prefetchMasterFor(Path activeFile) {
        if (activeFile == null) {
            return;
        }
        renderExecutor.submit(() -> {
            try {
                Path projectDir = findProjectDir(activeFile.getParent());
                Path resolved = projectDir != null ? pickMasterIn(projectDir, activeFile) : null;
                if (resolved == null) {
                    resolved = activeFile;
                }
                final Path finalResolved = resolved;
                Platform.runLater(() -> currentMaster.set(finalResolved));
            } catch (Exception e) {
                logger.debug("Master prefetch failed for {}", activeFile, e);
            }
        });
    }

    private void rasterizeAndShow() {
        double zoom = zoomSlider.getValue();
        float dpi = (float) (BASE_DPI * zoom);
        RasterizeResult res = rasterizePages(dpi);
        synchronized (cachedImages) {
            cachedImages.clear();
            cachedImages.addAll(res.images);
            cachedPagePtWidths.clear();
            cachedPagePtWidths.addAll(res.pageWidthsPt);
            cachedImagesDpi = dpi;
        }
        Platform.runLater(() -> populatePagesBox(zoom));
    }

    /**
     * Replace the page nodes with ImageViews sized for the requested zoom
     * level.  Uses the cached images; the underlying pixel data is the
     * size {@link #cachedImagesDpi} was rendered at, but the displayed
     * size is {@code pageWidthPt * zoom} (1 PDF pt = 1/72 inch, ImageView
     * units are device pixels at 96 dpi, and {@link #BASE_DPI} = 96 so
     * {@code displayPx = ptWidth * BASE_DPI / 72 * zoom}).
     */
    private void populatePagesBox(double zoom) {
        synchronized (cachedImages) {
            pagesBox.getChildren().clear();
            for (int i = 0; i < cachedImages.size(); i++) {
                ImageView view = new ImageView(cachedImages.get(i));
                view.setPreserveRatio(true);
                if (i < cachedPagePtWidths.size()) {
                    double targetPx = cachedPagePtWidths.get(i) * BASE_DPI / 72.0 * zoom;
                    view.setFitWidth(targetPx);
                    // Smooth interpolation when downscaling (zoom < cached);
                    // disabled when upscaling so the user sees the
                    // native pixel grid until the re-rasterise lands.
                    view.setSmooth(true);
                    view.setCache(true);
                }
                pagesBox.getChildren().add(view);
            }
        }
    }

    /**
     * O(n) walk over the displayed ImageViews adjusting their
     * {@code fitWidth} for the new zoom.  No PDFBox calls, no I/O —
     * this is what keeps the slider responsive while the user drags.
     */
    private void rescaleViewsInstantly(double zoom) {
        synchronized (cachedImages) {
            if (cachedPagePtWidths.isEmpty()) {
                return;
            }
            int n = pagesBox.getChildren().size();
            for (int i = 0; i < n && i < cachedPagePtWidths.size(); i++) {
                javafx.scene.Node node = pagesBox.getChildren().get(i);
                if (node instanceof ImageView iv) {
                    iv.setFitWidth(cachedPagePtWidths.get(i) * BASE_DPI / 72.0 * zoom);
                }
            }
        }
    }

    /**
     * Kick off a re-rasterise only if the new zoom level would benefit
     * visibly from sharper pixels.  Specifically: skip when zoom <= the
     * DPI we already rasterised at (downscale is sharp; upscale beyond
     * the cached DPI is blurry and warrants a fresh render).  Also
     * coalesces concurrent calls via {@link #reraserizing}.
     */
    private void rerasterizeIfQualityWouldImprove() {
        try {
            if (Files.size(tmpPdf) == 0) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        float wantDpi = (float) (BASE_DPI * zoomSlider.getValue());
        // Allow a small slack so 1.0 -> 1.05 doesn't trigger a re-render.
        if (wantDpi <= cachedImagesDpi * 1.05f) {
            return;
        }
        if (!reraserizing.compareAndSet(false, true)) {
            return;
        }
        renderExecutor.submit(() -> {
            try {
                rasterizeAndShow();
            } finally {
                reraserizing.set(false);
            }
        });
    }

    /** Result bundle so {@link #rasterizePages} can return both the images and their PDF page widths. */
    private record RasterizeResult(List<Image> images, List<Double> pageWidthsPt) {}

    private RasterizeResult rasterizePages(float dpi) {
        List<Image> images = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(tmpPdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                BufferedImage bim = renderer.renderImageWithDPI(i, dpi);
                images.add(SwingFXUtils.toFXImage(bim, null));
                widths.add((double) doc.getPage(i).getMediaBox().getWidth());
            }
        } catch (Exception e) {
            logger.warn("PDF rasterize failed", e);
        }
        return new RasterizeResult(images, widths);
    }

    @Override
    public void browse() {
        openInDesktop();
    }

    @Override
    public void browse(BrowserType browserType) {
        // Browser-type doesn't apply to a PDF preview; open in the OS default.
        openInDesktop();
    }

    private void openInDesktop() {
        try {
            if (Files.size(tmpPdf) > 0 && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(tmpPdf.toFile());
            }
        } catch (Exception e) {
            logger.warn("Could not open preview PDF in desktop viewer", e);
        }
    }

    @Override
    public void runScroller(String text) {
        // PDF preview has its own viewer-managed scroll; nothing to do.
    }

    @Override
    public void scrollByPosition(String text) {
        // No-op for PDF preview (scroll-sync is a v2 feature).
    }

    @Override
    public void scrollByLine(String text) {
        // No-op for PDF preview (scroll-sync is a v2 feature).
    }
}

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
    /** Page heights in PDF points, used to size placeholders before rasterise. */
    private final List<Double> cachedPagePtHeights = new ArrayList<>();
    /** Debounce timer for the heavy re-rasterise. Run on FX thread. */
    private javafx.animation.PauseTransition zoomDebounce;
    /** Tracks whether a re-rasterise is currently in flight on the executor. */
    private final java.util.concurrent.atomic.AtomicBoolean rerasterizing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Generation counter incremented on every fresh render or reraster.
     * Background tasks check this before publishing their result so a
     * slow page-N rasterise from generation G doesn't overwrite a
     * placeholder belonging to generation G+1.
     */
    private final java.util.concurrent.atomic.AtomicInteger renderGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Pages above and below the visible viewport that we pre-render so
     * scrolling reveals already-rendered pages without a placeholder
     * flash.  Two pages either side covers ~one screen of fast scroll.
     */
    private static final int PREFETCH_RADIUS = 2;

    /**
     * Maximum number of rendered page Images to keep in memory at once.
     * At 96 DPI a US-letter page is ~3-5 MB; capping at 24 keeps heap
     * pressure bounded for 500-page books while comfortably covering
     * any practical viewport + prefetch window.  Pages outside the
     * window are evicted back to placeholders and re-rendered on
     * demand if scrolled into view.
     */
    private static final int MAX_CACHED_PAGES = 24;

    /**
     * LRU access order for {@link #cachedImages}.  Page indices at the
     * head are the least-recently used and first to be evicted when
     * the cache exceeds {@link #MAX_CACHED_PAGES}.  Mutated only under
     * {@code synchronized (cachedImages)}.
     */
    private final java.util.LinkedHashSet<Integer> lruOrder = new java.util.LinkedHashSet<>();

    /**
     * Page indices that have a render task in flight.  Prevents
     * duplicate renders when the user scrolls back over a page whose
     * first render has not yet landed.
     */
    private final java.util.Set<Integer> inFlightPages =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Long-lived PDDocument handle so we don't pay the
     * {@code Loader.loadPDF} cost per page-render request.  Reopened
     * by {@link #rasterizeAndShow}; closed when superseded by a newer
     * generation.  Mutated only on the render executor.
     */
    private PDDocument cachedPdfHandle;
    private PDFRenderer cachedPdfRenderer;
    private int cachedPdfHandleGen = -1;

    /** Debounce timer for viewport-driven rasterise requests. */
    private javafx.animation.PauseTransition viewportDebounce;

    /** A page outline entry extracted from the rendered PDF's bookmarks tree. */
    public record PdfOutlineEntry(String title, int pageIndex,
                                  java.util.List<PdfOutlineEntry> children) {}

    /** Bookmarks for the currently-displayed PDF; rebuilt on every successful render. */
    private final List<PdfOutlineEntry> currentOutline =
            java.util.Collections.synchronizedList(new ArrayList<>());

    private javafx.scene.control.TreeView<PdfOutlineEntry> outlineTree;
    private javafx.scene.control.SplitPane splitPane;

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

            // Lazy-render trigger: any time the viewport moves or
            // resizes, request the now-visible pages.  Debounced so a
            // single drag of the scrollbar doesn't fire a request per
            // pixel.  80ms is short enough to feel "as you scroll"
            // and long enough to coalesce a smooth drag.
            viewportDebounce = new javafx.animation.PauseTransition(Duration.millis(80));
            viewportDebounce.setOnFinished(e -> requestVisiblePages());
            scrollPane.vvalueProperty().addListener((obs, ov, nv) -> viewportDebounce.playFromStart());
            scrollPane.viewportBoundsProperty().addListener((obs, ov, nv) -> viewportDebounce.playFromStart());

            buildLoadingDots();
            buildOutlineTree();

            Button refreshButton = new Button("Refresh");
            refreshButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Re-render the PDF preview now (F5)"));
            refreshButton.setOnAction(e -> render());

            // Outline toggle button — show/hide the bookmark navigation
            // panel.  Shown by default for any document with > 1 page;
            // collapsing the divider hides it without losing state.
            javafx.scene.control.ToggleButton outlineToggle =
                    new javafx.scene.control.ToggleButton("Outline");
            outlineToggle.setTooltip(new javafx.scene.control.Tooltip(
                    "Show / hide the PDF outline navigation panel"));
            outlineToggle.setSelected(true);

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
            //   left   = Refresh + Outline toggle
            //   center = loading dots (centered above the page area)
            //   right  = zoom controls

            HBox leftGroup = new HBox(6, refreshButton, outlineToggle);
            leftGroup.setAlignment(Pos.CENTER_LEFT);
            BorderPane toolbar = new BorderPane();
            toolbar.setLeft(leftGroup);
            toolbar.setCenter(loadingDots);
            toolbar.setRight(zoomGroup);
            BorderPane.setAlignment(leftGroup, Pos.CENTER_LEFT);
            BorderPane.setAlignment(loadingDots, Pos.CENTER);
            BorderPane.setAlignment(zoomGroup, Pos.CENTER_RIGHT);
            toolbar.setPadding(new Insets(4, 8, 4, 8));

            // SplitPane: outline (left) | pages scroll (right).
            // Divider position remembered across render-driven rebuilds
            // because we mutate items, never rebuild the SplitPane itself.
            splitPane = new javafx.scene.control.SplitPane(outlineTree, scrollPane);
            splitPane.setDividerPositions(0.22);
            javafx.scene.control.SplitPane.setResizableWithParent(outlineTree, false);

            outlineToggle.selectedProperty().addListener((obs, was, is) -> {
                if (Boolean.TRUE.equals(is)) {
                    if (!splitPane.getItems().contains(outlineTree)) {
                        splitPane.getItems().add(0, outlineTree);
                        splitPane.setDividerPositions(0.22);
                    }
                } else {
                    splitPane.getItems().remove(outlineTree);
                }
            });

            root.setTop(toolbar);
            root.setCenter(splitPane);
        });
    }

    /**
     * Build the outline TreeView. Empty until the first render populates it.
     * Selecting an entry scrolls the page area to the entry's page index.
     */
    private void buildOutlineTree() {
        javafx.scene.control.TreeItem<PdfOutlineEntry> root =
                new javafx.scene.control.TreeItem<>(new PdfOutlineEntry("Outline", -1, java.util.List.of()));
        root.setExpanded(true);
        outlineTree = new javafx.scene.control.TreeView<>(root);
        outlineTree.setShowRoot(false);
        outlineTree.setMinWidth(0);
        outlineTree.setPrefWidth(220);
        outlineTree.setCellFactory(tv -> new javafx.scene.control.TreeCell<PdfOutlineEntry>() {
            @Override
            protected void updateItem(PdfOutlineEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                if (item.pageIndex() >= 0) {
                    setText(item.title() + "  \u00b7  p" + (item.pageIndex() + 1));
                } else {
                    setText(item.title());
                }
            }
        });
        outlineTree.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (is != null && is.getValue() != null && is.getValue().pageIndex() >= 0) {
                scrollToPage(is.getValue().pageIndex());
            }
        });
    }

    /**
     * Replace the outline tree contents with the freshly-extracted PDF outline.
     * Empty list collapses the panel to a hint.
     */
    private void publishOutline(List<PdfOutlineEntry> entries) {
        Platform.runLater(() -> {
            javafx.scene.control.TreeItem<PdfOutlineEntry> root = outlineTree.getRoot();
            root.getChildren().clear();
            if (entries.isEmpty()) {
                root.getChildren().add(new javafx.scene.control.TreeItem<>(
                        new PdfOutlineEntry("(no outline in this PDF)", -1, java.util.List.of())));
                return;
            }
            for (PdfOutlineEntry e : entries) {
                root.getChildren().add(toTreeItem(e));
            }
        });
    }

    private javafx.scene.control.TreeItem<PdfOutlineEntry> toTreeItem(PdfOutlineEntry e) {
        javafx.scene.control.TreeItem<PdfOutlineEntry> ti = new javafx.scene.control.TreeItem<>(e);
        ti.setExpanded(true);
        for (PdfOutlineEntry c : e.children()) {
            ti.getChildren().add(toTreeItem(c));
        }
        return ti;
    }

    /**
     * Scroll the page area so the top of the requested page is in view.
     * Uses the cumulative height of preceding pages (incl. VBox spacing)
     * normalised against the scrollable content height.
     */
    private void scrollToPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pagesBox.getChildren().size()) {
            return;
        }
        // Defer to next pulse so layout has the up-to-date bounds.
        Platform.runLater(() -> {
            javafx.scene.Node target = pagesBox.getChildren().get(pageIndex);
            double targetY = target.getBoundsInParent().getMinY();
            double contentHeight = pagesBox.getBoundsInLocal().getHeight();
            double viewportHeight = scrollPane.getViewportBounds().getHeight();
            double scrollable = Math.max(1.0, contentHeight - viewportHeight);
            double v = Math.max(0.0, Math.min(1.0, targetY / scrollable));
            scrollPane.setVvalue(v);
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

    /**
     * Lazy viewport-driven render orchestrator.
     *
     * <p>Phase 1 (cheap, sync on the executor): open the PDF, read
     * page metadata + outline, close it.  Drives the placeholder
     * layout and outline tree, both of which we want visible
     * immediately.
     *
     * <p>Phase 2 (FX thread): build correctly-sized placeholders for
     * every page.  Scrollbar is now accurate and the user can scroll
     * before any pixels arrive.
     *
     * <p>Phase 3 (lazy): kick a single {@link #requestVisiblePages}
     * call.  From this point on, page rasterisation is driven
     * entirely by the viewport listener installed in
     * {@link #afterViewInit} \u2014 we render only the pages the user
     * is looking at, plus a small prefetch radius.  Pages outside the
     * window are evicted from the image cache once it exceeds
     * {@link #MAX_CACHED_PAGES} and re-rendered on demand.
     *
     * <p>A render-generation token discards in-flight tasks from a
     * superseded render so a slow page from doc N-1 cannot land in
     * doc N's layout.
     */
    private void rasterizeAndShow() {
        final int gen = renderGeneration.incrementAndGet();
        final double zoom = zoomSlider.getValue();

        // Phase 1: load metadata + outline.  Close immediately; the
        // long-lived handle is opened lazily on the first render
        // request so we don't pay the open cost twice.
        final List<Double> widths = new ArrayList<>();
        final List<Double> heights = new ArrayList<>();
        final List<PdfOutlineEntry> outline;
        final int pageCount;
        try (PDDocument doc = Loader.loadPDF(tmpPdf.toFile())) {
            pageCount = doc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                widths.add((double) doc.getPage(i).getMediaBox().getWidth());
                heights.add((double) doc.getPage(i).getMediaBox().getHeight());
            }
            outline = extractOutline(doc);
        } catch (Exception e) {
            logger.warn("PDF metadata load failed", e);
            return;
        }

        synchronized (cachedImages) {
            cachedImages.clear();
            for (int i = 0; i < pageCount; i++) {
                cachedImages.add(null);
            }
            cachedPagePtWidths.clear();
            cachedPagePtWidths.addAll(widths);
            cachedPagePtHeights.clear();
            cachedPagePtHeights.addAll(heights);
            cachedImagesDpi = (float) (BASE_DPI * zoom);
            currentOutline.clear();
            currentOutline.addAll(outline);
            lruOrder.clear();
        }
        inFlightPages.clear();
        closeCachedHandle(); // force reopen against the new tmpPdf

        publishOutline(outline);

        // Phase 2: build placeholders.
        Platform.runLater(() -> {
            if (gen != renderGeneration.get()) return;
            pagesBox.getChildren().clear();
            for (int i = 0; i < pageCount; i++) {
                pagesBox.getChildren().add(
                        buildPlaceholder(widths.get(i), heights.get(i), zoom));
            }
            // After the layout settles, ask for whichever pages are
            // visible.  Defer to next pulse so the viewport bounds
            // reflect the just-added placeholders.
            Platform.runLater(this::requestVisiblePages);
        });
    }

    /**
     * Compute the currently-visible page range and submit a render
     * request for each page in that range (plus
     * {@link #PREFETCH_RADIUS} either side) that is not already
     * cached or in flight.  Called on the FX thread by the viewport
     * listener and after every fresh render.
     */
    private void requestVisiblePages() {
        if (cachedPagePtHeights.isEmpty()) return;
        final int gen = renderGeneration.get();
        double zoom = zoomSlider.getValue();
        java.util.List<Double> heights;
        synchronized (cachedImages) {
            heights = new ArrayList<>(cachedPagePtHeights);
        }
        double spacing = pagesBox.getSpacing();
        var bounds = scrollPane.getViewportBounds();
        if (bounds == null) return; // pre-layout
        double viewportH = bounds.getHeight();
        double scrollV = scrollPane.getVvalue();
        int[] range = visiblePageRange(heights, scrollV, viewportH, zoom, spacing,
                BASE_DPI, PREFETCH_RADIUS);
        int from = range[0];
        int to = range[1];

        // Submit render requests for pages in [from, to] missing an image.
        for (int i = from; i <= to; i++) {
            final int idx = i;
            boolean needsRender;
            synchronized (cachedImages) {
                needsRender = idx < cachedImages.size() && cachedImages.get(idx) == null;
                if (needsRender) {
                    // Mark hot in LRU even before render lands; this
                    // prevents an immediate-eviction race when the
                    // window is exactly MAX_CACHED_PAGES wide.
                    touchLru(idx);
                }
            }
            if (!needsRender) continue;
            if (!inFlightPages.add(idx)) continue;
            renderExecutor.submit(() -> renderSinglePage(idx, gen, zoom));
        }
    }

    /**
     * Render a single page on the executor.  Uses the long-lived
     * {@link #cachedPdfHandle} (re-opening if needed) so we don't pay
     * the PDF-load cost per page.  Skips silently if a newer
     * generation has superseded this request.
     */
    private void renderSinglePage(int pageIndex, int gen, double zoom) {
        try {
            if (gen != renderGeneration.get()) return;
            PDFRenderer renderer = ensureCachedRenderer(gen);
            if (renderer == null) return;
            float dpi = (float) (BASE_DPI * zoom);
            BufferedImage bim = renderer.renderImageWithDPI(pageIndex, dpi);
            if (gen != renderGeneration.get()) return;
            Image fxImg = SwingFXUtils.toFXImage(bim, null);
            java.util.List<Integer> evicted;
            synchronized (cachedImages) {
                if (pageIndex >= cachedImages.size()) return;
                cachedImages.set(pageIndex, fxImg);
                touchLru(pageIndex);
                evicted = evictExcess();
            }
            Platform.runLater(() -> {
                if (gen != renderGeneration.get()) return;
                swapPlaceholder(pageIndex, fxImg, zoom);
                for (int e : evicted) {
                    restorePlaceholder(e, zoom);
                }
            });
        } catch (Exception e) {
            logger.debug("Single-page render failed for page {}", pageIndex, e);
        } finally {
            inFlightPages.remove(pageIndex);
        }
    }

    /**
     * Reopen the long-lived PDDocument if the cached handle belongs
     * to a stale generation (or hasn't been opened yet).  Returns the
     * renderer for the current generation, or {@code null} if open
     * failed or the generation has already moved on.
     *
     * <p>Called only from the render executor so single-thread
     * serialisation is implicit.
     */
    private synchronized PDFRenderer ensureCachedRenderer(int gen) {
        if (gen != renderGeneration.get()) return null;
        if (cachedPdfHandle != null && cachedPdfHandleGen == gen) {
            return cachedPdfRenderer;
        }
        closeCachedHandle();
        try {
            cachedPdfHandle = Loader.loadPDF(tmpPdf.toFile());
            cachedPdfRenderer = new PDFRenderer(cachedPdfHandle);
            cachedPdfHandleGen = gen;
            return cachedPdfRenderer;
        } catch (Exception e) {
            logger.warn("PDF handle reopen failed", e);
            return null;
        }
    }

    private synchronized void closeCachedHandle() {
        if (cachedPdfHandle != null) {
            try { cachedPdfHandle.close(); } catch (Exception ignored) { }
            cachedPdfHandle = null;
            cachedPdfRenderer = null;
            cachedPdfHandleGen = -1;
        }
    }

    /** Mark {@code pageIndex} as most-recently used. */
    private void touchLru(int pageIndex) {
        lruOrder.remove(pageIndex);
        lruOrder.add(pageIndex);
    }

    /**
     * Drop least-recently-used cached images until we are at or below
     * {@link #MAX_CACHED_PAGES}.  Returns the page indices that were
     * evicted so the caller can restore their placeholders on the FX
     * thread.  Must be called under {@code synchronized (cachedImages)}.
     */
    private java.util.List<Integer> evictExcess() {
        java.util.List<Integer> evicted = new ArrayList<>();
        java.util.Iterator<Integer> it = lruOrder.iterator();
        while (lruOrder.size() > MAX_CACHED_PAGES && it.hasNext()) {
            int idx = it.next();
            it.remove();
            if (idx < cachedImages.size() && cachedImages.get(idx) != null) {
                cachedImages.set(idx, null);
                evicted.add(idx);
            }
        }
        return evicted;
    }

    /**
     * Replace the rendered ImageView at {@code idx} with a
     * placeholder so the layout keeps the page slot at its correct
     * pixel size after eviction.  No-op if the index is out of range
     * or already shows a placeholder.
     */
    private void restorePlaceholder(int idx, double zoom) {
        if (idx < 0 || idx >= pagesBox.getChildren().size()) return;
        if (idx >= cachedPagePtWidths.size() || idx >= cachedPagePtHeights.size()) return;
        javafx.scene.Node existing = pagesBox.getChildren().get(idx);
        if (!(existing instanceof ImageView)) return;
        pagesBox.getChildren().set(idx,
                buildPlaceholder(cachedPagePtWidths.get(idx),
                        cachedPagePtHeights.get(idx), zoom));
    }

    /**
     * Pure helper: compute the inclusive [from, to] page index range
     * of pages currently intersecting the viewport, expanded by
     * {@code prefetch} pages either side and clamped to
     * {@code [0, pageCount)}.
     *
     * <p>Walks cumulative page heights ({@code heightPt * baseDpi/72 * zoom}
     * + spacing) until the running total crosses the viewport's top
     * and bottom edges in the scrollable region.
     *
     * <p>Visible-for-test (package-private) so the algorithm can be
     * pinned without spinning up a JavaFX toolkit.
     */
    static int[] visiblePageRange(java.util.List<Double> pagePtHeights,
                                  double scrollV, double viewportH,
                                  double zoom, double spacing,
                                  double baseDpi, int prefetch) {
        int n = pagePtHeights.size();
        if (n == 0) return new int[] {0, -1};
        // Cumulative pixel offsets [0..n], pageTops[i] = top of page i.
        double[] pageTops = new double[n + 1];
        for (int i = 0; i < n; i++) {
            double h = pagePtHeights.get(i) * baseDpi / 72.0 * zoom;
            pageTops[i + 1] = pageTops[i] + h + spacing;
        }
        double total = pageTops[n];
        double scrollable = Math.max(1.0, total - viewportH);
        double topY = Math.max(0.0, Math.min(scrollable, scrollV * scrollable));
        double bottomY = topY + Math.max(viewportH, 1.0);

        int from = 0;
        for (int i = 0; i < n; i++) {
            if (pageTops[i + 1] >= topY) { from = i; break; }
            from = i + 1;
        }
        int to = n - 1;
        for (int i = from; i < n; i++) {
            if (pageTops[i] >= bottomY) { to = i - 1; break; }
            to = i;
        }
        from = Math.max(0, from - prefetch);
        to = Math.min(n - 1, to + prefetch);
        if (from > to) {
            // Degenerate (e.g. zero viewport): at least include the first.
            from = Math.max(0, Math.min(n - 1, from));
            to = from;
        }
        return new int[] {from, to};
    }

    /**
     * Build a placeholder Region sized to the PDF page's media box at
     * the requested zoom.  Light grey background with a centered
     * "Loading\u2026 page N" label \u2014 the user sees document
     * structure (correct page sizes, scrollbar length) before any
     * pixels arrive.
     */
    private javafx.scene.Node buildPlaceholder(double widthPt, double heightPt, double zoom) {
        double w = widthPt * BASE_DPI / 72.0 * zoom;
        double h = heightPt * BASE_DPI / 72.0 * zoom;
        javafx.scene.layout.StackPane sp = new javafx.scene.layout.StackPane();
        sp.setPrefSize(w, h);
        sp.setMinSize(w, h);
        sp.setMaxSize(w, h);
        sp.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0;");
        Label loading = new Label("Loading\u2026");
        loading.setStyle("-fx-text-fill: #888;");
        sp.getChildren().add(loading);
        return sp;
    }

    /**
     * Replace the placeholder at {@code idx} with an ImageView showing
     * {@code img} sized for {@code zoom}.  No-op if the index is out of
     * range (e.g. the layout has been rebuilt by a newer render).
     */
    private void swapPlaceholder(int idx, Image img, double zoom) {
        if (idx < 0 || idx >= pagesBox.getChildren().size()) return;
        if (idx >= cachedPagePtWidths.size()) return;
        ImageView view = new ImageView(img);
        view.setPreserveRatio(true);
        double targetPx = cachedPagePtWidths.get(idx) * BASE_DPI / 72.0 * zoom;
        view.setFitWidth(targetPx);
        view.setSmooth(true);
        view.setCache(true);
        pagesBox.getChildren().set(idx, view);
    }

    /**
     * Extract the PDF's outline ({@code /Outlines} dictionary) into a
     * tree of {@link PdfOutlineEntry} for the navigation panel.
     * asciidoctor-pdf populates this with section bookmarks, so we get
     * a free chapter-by-chapter index.  Returns an empty list if the
     * document has no outline.
     */
    private List<PdfOutlineEntry> extractOutline(PDDocument doc) {
        org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline rootOutline =
                doc.getDocumentCatalog().getDocumentOutline();
        if (rootOutline == null) return java.util.List.of();
        List<PdfOutlineEntry> result = new ArrayList<>();
        var item = rootOutline.getFirstChild();
        while (item != null) {
            PdfOutlineEntry entry = outlineItemToEntry(item, doc);
            if (entry != null) result.add(entry);
            item = item.getNextSibling();
        }
        return result;
    }

    private PdfOutlineEntry outlineItemToEntry(
            org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item,
            PDDocument doc) {
        String title = item.getTitle();
        int pageIdx = -1;
        try {
            org.apache.pdfbox.pdmodel.PDPage destPage = item.findDestinationPage(doc);
            if (destPage != null) {
                pageIdx = doc.getPages().indexOf(destPage);
            }
        } catch (Exception ignored) {
            // Some outline items lack resolvable destinations; show without page link.
        }
        List<PdfOutlineEntry> children = new ArrayList<>();
        var child = item.getFirstChild();
        while (child != null) {
            PdfOutlineEntry sub = outlineItemToEntry(child, doc);
            if (sub != null) children.add(sub);
            child = child.getNextSibling();
        }
        if (title == null) title = "(untitled)";
        return new PdfOutlineEntry(title, pageIdx, children);
    }

    /**
     * O(n) walk over the displayed ImageViews adjusting their
     * {@code fitWidth} for the new zoom.  No PDFBox calls, no I/O —
     * this is what keeps the slider responsive while the user drags.
     * Placeholders are rescaled too so document length stays correct
     * before mid-render.
     */
    private void rescaleViewsInstantly(double zoom) {
        synchronized (cachedImages) {
            if (cachedPagePtWidths.isEmpty()) {
                return;
            }
            int n = pagesBox.getChildren().size();
            for (int i = 0; i < n && i < cachedPagePtWidths.size(); i++) {
                javafx.scene.Node node = pagesBox.getChildren().get(i);
                double wPx = cachedPagePtWidths.get(i) * BASE_DPI / 72.0 * zoom;
                if (node instanceof ImageView iv) {
                    iv.setFitWidth(wPx);
                } else if (node instanceof javafx.scene.layout.Region region
                        && i < cachedPagePtHeights.size()) {
                    double hPx = cachedPagePtHeights.get(i) * BASE_DPI / 72.0 * zoom;
                    region.setPrefSize(wPx, hPx);
                    region.setMinSize(wPx, hPx);
                    region.setMaxSize(wPx, hPx);
                }
            }
        }
    }

    /**
     * Kick off a re-rasterise only if the new zoom level would benefit
     * visibly from sharper pixels.  Specifically: skip when zoom <= the
     * DPI we already rasterised at (downscale is sharp; upscale beyond
     * the cached DPI is blurry and warrants a fresh render).  Also
     * coalesces concurrent calls via {@link #rerasterizing}.
     *
     * <p>Zoom-up is now cheap because we only ever re-render the
     * cached (visible-window) pages, not the whole document.  We
     * invalidate the cached images and let
     * {@link #requestVisiblePages} populate them lazily as the user
     * scrolls.
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
        if (!rerasterizing.compareAndSet(false, true)) {
            return;
        }
        renderExecutor.submit(() -> {
            try {
                final int gen = renderGeneration.incrementAndGet();
                final double zoom = zoomSlider.getValue();
                final int pageCount;
                synchronized (cachedImages) {
                    pageCount = cachedImages.size();
                    for (int i = 0; i < pageCount; i++) {
                        cachedImages.set(i, null);
                    }
                    lruOrder.clear();
                    cachedImagesDpi = (float) (BASE_DPI * zoom);
                }
                inFlightPages.clear();
                closeCachedHandle();
                Platform.runLater(() -> {
                    if (gen != renderGeneration.get()) return;
                    // Restore every slot to a placeholder sized for
                    // the new zoom; the viewport listener will drive
                    // re-render of whatever is in view.
                    for (int i = 0; i < pageCount; i++) {
                        restorePlaceholder(i, zoom);
                    }
                    requestVisiblePages();
                });
            } finally {
                rerasterizing.set(false);
            }
        });
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

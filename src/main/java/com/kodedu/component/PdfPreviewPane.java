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
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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

    private final VBox pagesBox = new VBox(8);
    private final ScrollPane scrollPane = new ScrollPane(pagesBox);
    private final Label statusLabel = new Label("PDF preview ready.");
    private final Slider zoomSlider = new Slider(0.5, 2.0, 1.0);

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

            zoomSlider.setPrefWidth(140);
            zoomSlider.setShowTickMarks(true);
            zoomSlider.setBlockIncrement(0.1);
            zoomSlider.valueProperty().addListener((obs, ov, nv) -> {
                if (Math.abs(nv.doubleValue() - ov.doubleValue()) > 0.001) {
                    rerasterizeAtCurrentZoom();
                }
            });

            HBox toolbar = new HBox(8, statusLabel, new Label("Zoom"), zoomSlider);
            toolbar.setAlignment(Pos.CENTER_LEFT);
            toolbar.setPadding(new Insets(4, 8, 4, 8));
            HBox.setHgrow(statusLabel, Priority.ALWAYS);

            root.setTop(toolbar);
            root.setCenter(scrollPane);
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
        setStatusLater("Rendering…");
        Future<?> next = renderExecutor.submit(this::renderNow);
        inFlight.set(next);
    }

    private void renderNow() {
        try {
            String asciidoc = current.currentEditorValue();
            if (asciidoc == null) {
                setStatusLater("No active document.");
                return;
            }
            Path masterAdoc = resolveMasterAdoc();
            if (masterAdoc == null) {
                setStatusLater("PDF preview unavailable — no master adoc.");
                return;
            }
            Path activeFile = current.currentTab() != null ? current.currentTab().getPath() : null;
            PreviewScope scope = previewConfigBean.getPdfPreviewScope();

            PreviewSourceResolver.Resolved r =
                    PreviewSourceResolver.resolve(scope, masterAdoc, activeFile, asciidoc);

            int hash = (r.scopeUsed().name() + "|" + r.source()).hashCode();
            if (hash == lastRenderHash && Files.size(tmpPdf) > 0) {
                setStatusLater("Up to date (" + r.scopeUsed() + ").");
                return;
            }

            long t0 = System.currentTimeMillis();
            pdfRenderer.renderTo(r.source(), r.baseDir(), tmpPdf);
            lastRenderHash = hash;
            long elapsed = System.currentTimeMillis() - t0;
            String label = r.scopeUsed() + " preview · " + elapsed + " ms"
                    + (r.notice() != null ? " · " + r.notice() : "");
            logger.info("PDF preview rendered ({}, {} ms)", r.scopeUsed(), elapsed);

            rasterizeAndShow(label);
        } catch (Exception e) {
            logger.warn("PDF preview render failed (keeping previous pages visible)", e);
            setStatusLater("Render failed: " + e.getMessage());
        }
    }

    private Path resolveMasterAdoc() {
        // Heuristic: walk up from the active file's parent looking for a
        // .asciidoctorconfig (the project marker) and pick the longest .adoc
        // file in that directory as the master.
        if (current.currentTab() == null) {
            return null;
        }
        Path active = current.currentTab().getPath();
        if (active == null) {
            return null;
        }
        Path workdir = current.currentTab().getParentOrWorkdir();
        if (workdir == null) {
            return active;
        }
        Path dir = workdir;
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".asciidoctorconfig"))
                    || Files.exists(dir.resolve(".asciidoctorconfig.adoc"))) {
                Path candidate = pickMasterIn(dir, active);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return active;
    }

    private Path pickMasterIn(Path projectDir, Path active) {
        Path activeAbs = active.toAbsolutePath().normalize();
        try (var stream = Files.newDirectoryStream(projectDir, "*.adoc")) {
            Path best = null;
            for (Path candidate : stream) {
                if (candidate.toAbsolutePath().normalize().equals(activeAbs)) {
                    continue;
                }
                if (best == null || candidate.getFileName().toString().length()
                        > best.getFileName().toString().length()) {
                    best = candidate;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void rasterizeAndShow(String statusText) {
        float dpi = (float) (BASE_DPI * zoomSlider.getValue());
        List<Image> images = rasterizePages(dpi);
        Platform.runLater(() -> {
            pagesBox.getChildren().clear();
            for (Image img : images) {
                ImageView view = new ImageView(img);
                view.setPreserveRatio(true);
                pagesBox.getChildren().add(view);
            }
            statusLabel.setText(statusText + " · " + images.size() + " pages");
        });
    }

    private void rerasterizeAtCurrentZoom() {
        try {
            if (Files.size(tmpPdf) == 0) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        renderExecutor.submit(() ->
                rasterizeAndShow(String.format("Zoom %.1fx", zoomSlider.getValue())));
    }

    private List<Image> rasterizePages(float dpi) {
        List<Image> images = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(tmpPdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                BufferedImage bim = renderer.renderImageWithDPI(i, dpi);
                images.add(SwingFXUtils.toFXImage(bim, null));
            }
        } catch (Exception e) {
            logger.warn("PDF rasterize failed", e);
        }
        return images;
    }

    private void setStatusLater(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
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

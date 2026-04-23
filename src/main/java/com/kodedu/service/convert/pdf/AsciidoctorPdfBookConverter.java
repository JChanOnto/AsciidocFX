package com.kodedu.service.convert.pdf;

import com.kodedu.component.PdfPreviewPane;
import com.kodedu.controller.ApplicationController;
import com.kodedu.other.Current;
import com.kodedu.other.ExtensionFilters;
import com.kodedu.other.RenderResult;
import com.kodedu.service.DirectoryService;
import com.kodedu.service.ThreadService;
import com.kodedu.service.convert.DocumentConverter;
import com.kodedu.service.ui.IndikatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * "Save → PDF" entry point.  All actual rendering is delegated to
 * {@link PdfRenderer} so the live PDF preview pane shares the exact same
 * pipeline (single source of truth: theme, attributes, diagrams).
 */
@Component
public class AsciidoctorPdfBookConverter implements DocumentConverter<RenderResult> {

    private final Logger logger = LoggerFactory.getLogger(AsciidoctorPdfBookConverter.class);

    private final ApplicationController asciiDocController;
    private final IndikatorService indikatorService;
    private final ThreadService threadService;
    private final DirectoryService directoryService;
    private final Current current;
    private final PdfRenderer pdfRenderer;
    private final PdfPreviewPane pdfPreviewPane;

    @Autowired
    public AsciidoctorPdfBookConverter(final ApplicationController asciiDocController,
                            final IndikatorService indikatorService,
                            final PdfRenderer pdfRenderer,
                            final ThreadService threadService,
                            final DirectoryService directoryService,
                            final Current current,
                            @Lazy final PdfPreviewPane pdfPreviewPane) {
        this.asciiDocController = asciiDocController;
        this.indikatorService = indikatorService;
        this.threadService = threadService;
        this.directoryService = directoryService;
        this.current = current;
        this.pdfRenderer = pdfRenderer;
        this.pdfPreviewPane = pdfPreviewPane;
    }


    @Override
    public void convert(boolean askPath, Consumer<RenderResult>... nextStep) {

        // Resolve the save path, source, and baseDir on the caller
        // thread (matches HtmlBookConverter / DocBookConverter).  Doing
        // this inside the virtual-thread task breaks when spring-boot:run
        // is the launcher: the forked JVM's virtual-thread context
        // classloader fails to resolve project classes referenced for
        // the first time inside the lambda, yielding
        //   NoClassDefFoundError: com/kodedu/other/ExtensionFilters
        // from an attempt to read ExtensionFilters.PDF in the task body.
        // directoryService.getSaveOutputPath / current.currentEditorValue
        // already handle FX-thread trips internally, so there's no
        // reason to cross the virtual-thread boundary first.
        final Path pdfPath = directoryService.getSaveOutputPath(ExtensionFilters.PDF, askPath);
        final File destFile = pdfPath.toFile();

        // Reuse the preview pane's master + scope resolution so
        // Save->PDF matches "what you see is what you save".  Without
        // this, Save on a chapter file fed the renderer the raw
        // `== Chapter` fragment with the chapter's own folder as
        // baseDir - so the saved PDF came out with no diagrams
        // (`[{diagram}]` undefined), no screenshots (no :imagesdir:),
        // and no theme, even though the preview rendered fine.
        final java.util.Optional<com.kodedu.service.preview.PreviewSourceResolver.Resolved> resolved =
                pdfPreviewPane.resolveCurrentRenderSource();

        final String asciidoc = resolved.map(r -> r.source())
                .orElseGet(current::currentEditorValue);
        final Path workdir = resolved.map(r -> r.baseDir())
                .orElseGet(() -> current.currentTab().getParentOrWorkdir());

        threadService.runTaskLater(() -> {

            indikatorService.startProgressBar();
            // Also light up the PDF preview pane's loading dots: the
            // global progressBar is a 1-pixel-tall strip wedged between
            // the toolbar and the preview that's easy to miss.  CRuby
            // asciidoctor-pdf can take 5-30s on a large book; without
            // this hold the user gets no obvious feedback that
            // anything is happening.
            AutoCloseable previewLoading = pdfPreviewPane.holdLoading();
            if (resolved.isPresent()) {
                logger.debug("PDF conversion started (scope={}, baseDir={})",
                        resolved.get().scopeUsed(), workdir);
            } else {
                logger.debug("PDF conversion started (raw editor buffer; no master resolved)");
            }

            try {
                pdfRenderer.renderTo(asciidoc, workdir, pdfPath);
                asciiDocController.addRemoveRecentList(pdfPath);
                onSuccessfulConversation(nextStep, destFile);
            } catch (Exception e) {
                logger.error("Problem occured while converting to PDF", e);
                onFailedConversation(nextStep, e);
            } finally {
                try { previewLoading.close(); } catch (Exception ignored) { }
                indikatorService.stopProgressBar();
                logger.debug("PDF conversion ended");
            }

        });
    }

}

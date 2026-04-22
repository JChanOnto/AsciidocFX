package com.kodedu.service.convert.pdf;

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

    @Autowired
    public AsciidoctorPdfBookConverter(final ApplicationController asciiDocController,
                            final IndikatorService indikatorService,
                            final PdfRenderer pdfRenderer,
                            final ThreadService threadService,
                            final DirectoryService directoryService,
                            final Current current) {
        this.asciiDocController = asciiDocController;
        this.indikatorService = indikatorService;
        this.threadService = threadService;
        this.directoryService = directoryService;
        this.current = current;
        this.pdfRenderer = pdfRenderer;
    }


    @Override
    public void convert(boolean askPath, Consumer<RenderResult>... nextStep) {

        String asciidoc = current.currentEditorValue();

        threadService.runTaskLater(() -> {

            final Path pdfPath = directoryService.getSaveOutputPath(ExtensionFilters.PDF, askPath);

            File destFile = pdfPath.toFile();

            Path workdir = current.currentTab().getParentOrWorkdir();

            indikatorService.startProgressBar();
            logger.debug("PDF conversion started");

            try {
                pdfRenderer.renderTo(asciidoc, workdir, pdfPath);
                asciiDocController.addRemoveRecentList(pdfPath);
                onSuccessfulConversation(nextStep, destFile);
            } catch (Exception e) {
                logger.error("Problem occured while converting to PDF", e);
                onFailedConversation(nextStep, e);
            } finally {
                indikatorService.stopProgressBar();
                logger.debug("PDF conversion ended");
            }

        });
    }

}

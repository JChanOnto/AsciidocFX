package com.kodedu.service.convert.pdf;

import com.kodedu.config.PdfConfigBean;
import com.kodedu.service.extension.processor.ExtensionPreprocessor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

import static com.kodedu.helper.AsciidoctorHelper.convertSafe;
import static com.kodedu.service.AsciidoctorFactory.getNonHtmlDoctor;

/**
 * Renders an AsciiDoc string to a PDF file via {@code asciidoctor-pdf}.
 *
 * <p>Single source of truth for the PDF render pipeline.  Both the
 * {@link AsciidoctorPdfBookConverter "Save → PDF"} action and the live PDF
 * preview pane call this so they cannot drift out of sync (same theme, same
 * attributes, same diagram pipeline).
 *
 * <p>This call is synchronous and runs on the calling thread.  Callers are
 * responsible for off-FX-thread scheduling and cancellation.  Calls into
 * {@code asciidoctor-pdf} are effectively single-threaded (JRuby), so a
 * dedicated single-thread executor on the caller side is the recommended
 * pattern.
 */
@Component
public class PdfRenderer {

    private static final Logger logger = LoggerFactory.getLogger(PdfRenderer.class);

    private final PdfConfigBean pdfConfigBean;

    @Autowired
    public PdfRenderer(PdfConfigBean pdfConfigBean) {
        this.pdfConfigBean = pdfConfigBean;
    }

    /**
     * Render {@code asciidoc} to {@code outPdf}.
     *
     * @param asciidoc source text (the parser sees this verbatim after the
     *                 {@link ExtensionPreprocessor} pass)
     * @param baseDir  resource resolution root (typically the open document's
     *                 parent dir).  Must be a real directory.
     * @param outPdf   target PDF path.  Parent directory must exist.
     * @throws RuntimeException wrapping any conversion error
     */
    public void renderTo(String asciidoc, Path baseDir, Path outPdf) {
        Objects.requireNonNull(asciidoc, "asciidoc");
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(outPdf, "outPdf");

        File destFile = outPdf.toFile();
        SafeMode safe = convertSafe(pdfConfigBean.getSafe());
        Attributes attributes = pdfConfigBean.getAsciiDocAttributes(asciidoc);
        Options options = Options.builder()
                .baseDir(baseDir.toFile())
                .toFile(destFile)
                .backend("pdf")
                .safe(safe)
                .sourcemap(pdfConfigBean.getSourcemap())
                .headerFooter(pdfConfigBean.getHeader_footer())
                .attributes(attributes)
                .build();
        String content = ExtensionPreprocessor.correctExtensionBlocks(asciidoc);
        long t0 = System.currentTimeMillis();
        getNonHtmlDoctor().convert(content, options);
        logger.debug("PDF render: {} -> {} in {} ms", baseDir, outPdf,
                System.currentTimeMillis() - t0);
    }
}

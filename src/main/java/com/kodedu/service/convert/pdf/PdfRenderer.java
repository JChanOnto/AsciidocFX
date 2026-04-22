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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * <p>Two render paths, in order of preference:
 * <ol>
 *   <li><b>External CRuby</b> when {@link PdfConfigBean#getPdfRendererCommand()}
 *       is non-blank, or a bundled
 *       {@code <install-dir>/ruby/bin/asciidoctor-pdf} is auto-detected.
 *       CRuby Prawn is typically 2-5× faster than JRuby Prawn.</li>
 *   <li><b>In-process JRuby</b> via {@code asciidoctorj-pdf} (legacy upstream
 *       behavior).  Zero install, slower per render.</li>
 * </ol>
 *
 * <p>This call is synchronous and runs on the calling thread.  Callers are
 * responsible for off-FX-thread scheduling and cancellation.
 */
@Component
public class PdfRenderer {

    private static final Logger logger = LoggerFactory.getLogger(PdfRenderer.class);

    private final PdfConfigBean pdfConfigBean;

    /** Cached lookup of the bundled CRuby (null if absent at startup). */
    private final Path bundledRuby = BundledRubyResolver.find();

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

        long t0 = System.currentTimeMillis();
        List<String> externalCmd = resolveExternalCommand();
        if (externalCmd != null) {
            renderViaExternal(externalCmd, asciidoc, baseDir, outPdf);
            logger.debug("PDF render (external {}): {} -> {} in {} ms",
                    externalCmd.get(0), baseDir, outPdf, System.currentTimeMillis() - t0);
        } else {
            renderViaJRuby(asciidoc, baseDir, outPdf);
            logger.debug("PDF render (jruby): {} -> {} in {} ms",
                    baseDir, outPdf, System.currentTimeMillis() - t0);
        }
    }

    /**
     * Resolve the external command, in priority order:
     * <ol>
     *   <li>User setting {@code pdfRendererCommand} (whitespace-split)</li>
     *   <li>Bundled CRuby at {@code <install-dir>/ruby/bin/asciidoctor-pdf}</li>
     *   <li>{@code null} → caller falls back to the JRuby path</li>
     * </ol>
     */
    private List<String> resolveExternalCommand() {
        String userCmd = pdfConfigBean.getPdfRendererCommand();
        if (userCmd != null && !userCmd.isBlank()) {
            return new ArrayList<>(Arrays.asList(userCmd.trim().split("\\s+")));
        }
        if (bundledRuby != null) {
            return new ArrayList<>(List.of(bundledRuby.toString()));
        }
        return null;
    }

    private void renderViaJRuby(String asciidoc, Path baseDir, Path outPdf) {
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
        getNonHtmlDoctor().convert(content, options);
    }

    /**
     * Shell out to a CRuby {@code asciidoctor-pdf}.  The source is written to
     * a temp file inside {@code baseDir} so {@code include::}, {@code imagesdir},
     * and {@code {docdir}} resolve identically to the in-process path.
     *
     * <p>Every attribute the JRuby path would have applied is forwarded as a
     * {@code -a name=value} flag, so theme, diagram cache dir, and any
     * project-discovered settings carry over.
     */
    private void renderViaExternal(List<String> command, String asciidoc, Path baseDir, Path outPdf) {
        String content = ExtensionPreprocessor.correctExtensionBlocks(asciidoc);
        Path tmpInput = null;
        try {
            tmpInput = Files.createTempFile(baseDir, "afx-render-", ".adoc");
            Files.writeString(tmpInput, content, StandardCharsets.UTF_8);

            List<String> argv = new ArrayList<>(command);
            argv.add("-r"); argv.add("asciidoctor-diagram");
            argv.add("--safe-mode"); argv.add(pdfConfigBean.getSafe());
            if (Boolean.TRUE.equals(pdfConfigBean.getSourcemap())) {
                argv.add("-a"); argv.add("sourcemap");
            }
            if (Boolean.FALSE.equals(pdfConfigBean.getHeader_footer())) {
                argv.add("-s");
            }

            Attributes attrs = pdfConfigBean.getAsciiDocAttributes(asciidoc);
            for (Map.Entry<String, Object> e : attrs.map().entrySet()) {
                Object v = e.getValue();
                if (v == null || Boolean.FALSE.equals(v)) {
                    argv.add("-a"); argv.add(e.getKey() + "!");
                } else if (Boolean.TRUE.equals(v)) {
                    argv.add("-a"); argv.add(e.getKey());
                } else {
                    argv.add("-a"); argv.add(e.getKey() + "=" + v);
                }
            }

            Path outDir = outPdf.toAbsolutePath().getParent();
            argv.add("-D"); argv.add(outDir.toString());
            argv.add("-o"); argv.add(outPdf.getFileName().toString());
            argv.add(tmpInput.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(baseDir.toFile())
                    .redirectErrorStream(true);
            // Suppress Ruby 4.0 fiddle/import deprecation noise (matches the
            // build_pdf.ps1 pattern used by reference projects).
            pb.environment().putIfAbsent("RUBYOPT", "-W0");

            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                throw new RuntimeException("External asciidoctor-pdf failed (exit "
                        + exit + "):\n" + output);
            }
            if (!output.isBlank()) {
                logger.debug("asciidoctor-pdf output:\n{}", output);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("External PDF render failed: " + e.getMessage(), e);
        } finally {
            if (tmpInput != null) {
                try { Files.deleteIfExists(tmpInput); } catch (IOException ignored) { }
            }
        }
    }
}

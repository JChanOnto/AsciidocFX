package com.kodedu.service.convert.pdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates a CRuby `asciidoctor-pdf` executable that the install4j package may
 * have shipped alongside AsciidocFX.
 *
 * <p>The {@code install4j-package} Maven profile copies
 * {@code ruby-runtime/<os>/} (produced by the {@code bundle_ruby_runtime}
 * scripts) into {@code <install-dir>/ruby/}.  When that directory exists at
 * runtime we point {@link PdfRenderer} at it for the fast native-Ruby PDF
 * render path.  No bundled runtime → {@link PdfRenderer} stays on the
 * in-process JRuby path, which is the legacy behavior.
 *
 * <p>The lookup is best-effort and fully optional.  Any I/O error or missing
 * file just yields {@link #find()} returning {@code null}.
 */
final class BundledRubyResolver {

    private static final Logger logger = LoggerFactory.getLogger(BundledRubyResolver.class);

    private BundledRubyResolver() {}

    /**
     * @return absolute path to a bundled {@code asciidoctor-pdf} (or
     *         {@code .bat} on Windows), or {@code null} if not found.
     */
    static Path find() {
        Path installDir = guessInstallDir();
        if (installDir == null) {
            logger.info("No bundled Ruby: install dir not found (running outside appassembler layout?)");
            return null;
        }
        Path rubyBin = installDir.resolve("ruby").resolve("bin");
        if (!Files.isDirectory(rubyBin)) {
            logger.info("No bundled Ruby: {} does not exist", rubyBin);
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path[] candidates = windows
                ? new Path[] { rubyBin.resolve("asciidoctor-pdf.bat"), rubyBin.resolve("asciidoctor-pdf.cmd") }
                : new Path[] { rubyBin.resolve("asciidoctor-pdf") };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                logger.info("Bundled asciidoctor-pdf found: {}", c);
                return c.toAbsolutePath();
            }
        }
        logger.info("No bundled Ruby: asciidoctor-pdf launcher missing in {}", rubyBin);
        return null;
    }

    /**
     * Best-effort: locate the appassembler {@code <install-dir>}.
     *
     * <p>Two layouts are recognised:
     * <ol>
     *   <li><b>Installed</b>: walk up from the class's source location
     *       looking for {@code <dir>/lib} + {@code <dir>/conf} siblings
     *       (the appassembler layout) and return that {@code <dir>} —
     *       {@code <dir>/ruby/} sits next to them.</li>
     *   <li><b>Dev (mvn spring-boot:run)</b>: classes live in
     *       {@code <repo>/target/classes/}, which has no {@code lib/conf}
     *       siblings.  Walk up looking instead for a
     *       {@code target/appassembler} sibling and return that — the
     *       {@code ruby/} the {@code install4j-package} profile copied
     *       lives at {@code <repo>/target/appassembler/ruby/}.</li>
     * </ol>
     */
    private static Path guessInstallDir() {
        try {
            Path codeSource = Paths.get(BundledRubyResolver.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
                // Installed layout
                if (Files.isDirectory(dir.resolve("lib")) && Files.isDirectory(dir.resolve("conf"))) {
                    return dir;
                }
                // Dev / appassembler layout
                Path appassembler = dir.resolve("target").resolve("appassembler");
                if (Files.isDirectory(appassembler)) {
                    return appassembler;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not locate appassembler install dir: {}", e.getMessage());
        }
        return null;
    }
}

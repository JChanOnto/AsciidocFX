package com.kodedu.config;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.asciidoctor.log.LogHandler;
import org.asciidoctor.log.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the chapter-preview "include file not found"
 * spam: {@link AsciidoctorConfigBase#getAsciiDocAttributes(String)}
 * does a parse to extract the document attribute table.  Before the
 * fix that parse was a full-content load() with the chapter file's
 * directory as baseDir, which caused asciidoctor to try to follow
 * every {@code include::sections/...[]} in the synthesised wrapper
 * relative to {@code <projectRoot>/sections}, producing doubled paths
 * like {@code <projectRoot>/sections/sections/_attributes.adoc} and
 * an ERROR log entry per missing include.
 *
 * <p>Fix: pass {@code parseHeaderOnly(true)} so the load skips the
 * body and never resolves includes.  We only need the header
 * attribute table; the actual render takes care of include
 * resolution with the correct baseDir.
 *
 * <p>These tests bracket the contract:
 * <ul>
 *   <li>full parse with mismatched baseDir logs an ERROR per
 *       unresolved include (demonstrates the bug);</li>
 *   <li>header-only parse against the same input logs nothing;</li>
 *   <li>both parses still surface the document's header attributes
 *       (so the production code can use parseHeaderOnly without
 *       losing the attribute table it actually needs).</li>
 * </ul>
 */
class AsciidoctorAttributeExtractIncludeTest {

    private Asciidoctor doctor;
    private final List<LogRecord> log = new ArrayList<>();
    private final LogHandler handler = log::add;

    @BeforeEach
    void setUp() {
        doctor = Asciidoctor.Factory.create();
        doctor.registerLogHandler(handler);
    }

    @AfterEach
    void tearDown() {
        if (doctor != null) {
            doctor.unregisterLogHandler(handler);
            doctor.shutdown();
        }
    }

    /** Stand-in for the chapter wrapper produced by {@code PreviewSourceResolver}. */
    private static final String WRAPPER_WITH_INCLUDES = """
            = Demo
            :doctype: book
            :pdf-theme: custom

            include::sections/_attributes.adoc[]
            include::sections/01-overview.adoc[]
            """;

    @Test
    void fullParseWithMismatchedBaseDirLogsIncludeNotFoundErrors(@TempDir Path tmp) throws Exception {
        // Mismatched baseDir: chapter parent (sections/) instead of
        // project root.  The wrapper's `include::sections/...` paths
        // therefore resolve under sections/sections/... which doesn't
        // exist - reproducing the user-visible spam.
        Path sections = Files.createDirectory(tmp.resolve("sections"));

        doctor.load(WRAPPER_WITH_INCLUDES, Options.builder()
                .safe(SafeMode.UNSAFE)
                .baseDir(sections.toFile())
                .attributes(Attributes.builder().allowUriRead(true).build())
                .build());

        long includeErrors = log.stream()
                .filter(r -> r.getMessage() != null
                        && r.getMessage().contains("include file not found"))
                .count();
        assertTrue(includeErrors >= 2,
                "Pre-fix bug should produce >=2 'include file not found' "
                        + "errors against the wrong baseDir, got " + includeErrors
                        + " (records: " + log + ")");
    }

    @Test
    void headerOnlyParseAgainstSameInputLogsNoIncludeErrors(@TempDir Path tmp) throws Exception {
        Path sections = Files.createDirectory(tmp.resolve("sections"));

        doctor.load(WRAPPER_WITH_INCLUDES, Options.builder()
                .safe(SafeMode.UNSAFE)
                .parseHeaderOnly(true)
                .baseDir(sections.toFile())
                .attributes(Attributes.builder().allowUriRead(true).build())
                .build());

        long includeErrors = log.stream()
                .filter(r -> r.getMessage() != null
                        && r.getMessage().contains("include file not found"))
                .count();
        assertFalse(includeErrors > 0,
                "parseHeaderOnly must skip include resolution; got "
                        + includeErrors + " include errors: " + log);
    }

    @Test
    void headerOnlyParseStillExposesDocumentHeaderAttributes(@TempDir Path tmp) throws Exception {
        Path sections = Files.createDirectory(tmp.resolve("sections"));

        var document = doctor.load(WRAPPER_WITH_INCLUDES, Options.builder()
                .safe(SafeMode.UNSAFE)
                .parseHeaderOnly(true)
                .baseDir(sections.toFile())
                .attributes(Attributes.builder().allowUriRead(true).build())
                .build());

        // The header attribute table is the whole reason this parse
        // exists; if parseHeaderOnly hid it we'd have traded one bug
        // for another.
        assertTrue("custom".equals(document.getAttributes().get("pdf-theme")),
                "header attribute pdf-theme must be exposed by parseHeaderOnly; got "
                        + document.getAttributes());
        assertTrue("book".equals(document.getAttributes().get("doctype")),
                "header attribute doctype must be exposed by parseHeaderOnly; got "
                        + document.getAttributes());
    }
}

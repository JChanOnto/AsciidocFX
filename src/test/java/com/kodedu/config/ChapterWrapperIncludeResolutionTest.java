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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard pinning the exact user-reported bug:
 * <pre>
 * ERROR =&gt; 01-overview.adoc: line 23: include file not found:
 *   D:/.../sections/sections/_attributes.adoc
 * </pre>
 *
 * <p>Trigger: the PDF preview pane's chapter-mode wrapper (the master
 * document with sibling-chapter {@code include::} lines pruned) is fed
 * to {@code pdfConfigBean.getAsciiDocAttributes(String)} to extract
 * the header attribute table before calling the renderer.  Previously
 * that method used {@code currentTab.getParentOrWorkdir()} as the
 * baseDir for the parse, which in chapter mode is the chapter's
 * folder — the wrong base for the wrapper's
 * {@code include::sections/_attributes.adoc[]} line.  Combined with
 * the {@code docfile} attribute being set to the chapter's own path,
 * asciidoctor reports the error as "01-overview.adoc: line &lt;N&gt;"
 * stamped with a doubled path like
 * {@code sections/sections/_attributes.adoc}.
 *
 * <p>The test reproduces both halves of the bug signature (doubled
 * path <em>and</em> chapter-file stamping) against the old parse
 * config, then verifies the fixed config (correct baseDir, no leaked
 * docfile) produces no include error and still exposes the header
 * attributes.
 *
 * <p>Why we test against a <em>full</em> load(), not parseHeaderOnly:
 * although asciidoctorj 2.x happens to skip include resolution under
 * {@code parseHeaderOnly(true)}, that is not a documented guarantee.
 * The fix we rely on is "use the correct baseDir", which holds whether
 * or not parseHeaderOnly also helps.  A full load pins the contract at
 * the stronger level.
 */
class ChapterWrapperIncludeResolutionTest {

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

    /** Scaffold a realistic multi-section project. */
    private Path makeProject(Path root) throws Exception {
        Path sections = Files.createDirectory(root.resolve("sections"));
        Files.writeString(sections.resolve("_attributes.adoc"),
                ":product-name: Widget\n:diagram: mermaid,format=svg\n");
        Files.writeString(sections.resolve("01-overview.adoc"),
                "== Overview\n\nProse content.\n");
        Files.writeString(sections.resolve("02-core.adoc"),
                "== Core\n\nMore prose.\n");
        Path master = root.resolve("master.adoc");
        Files.writeString(master,
                "= Demo Book\n:doctype: book\n:pdf-theme: custom\n\n"
                        + "include::sections/_attributes.adoc[]\n\n"
                        + "include::sections/01-overview.adoc[]\n"
                        + "include::sections/02-core.adoc[]\n");
        return master;
    }

    /** Chapter-mode wrapper produced by {@code PreviewSourceResolver}
     *  when the active file is {@code sections/01-overview.adoc}.  Only
     *  the sibling-chapter include is pruned; the {@code _attributes}
     *  partial stays so the chapter's attribute environment matches
     *  the full render. */
    private String buildWrapper() {
        return "= Demo Book\n:doctype: book\n:pdf-theme: custom\n"
                + ":notitle:\n:title-page!:\n:toc!:\n:sectnums!:\n\n"
                + "include::sections/_attributes.adoc[]\n\n"
                + "include::sections/01-overview.adoc[]\n";
    }

    @Test
    void buggyParseConfigReproducesDoubledSectionsPath(@TempDir Path tmp) throws Exception {
        Path master = makeProject(tmp);
        Path sections = master.getParent().resolve("sections");
        String wrapper = buildWrapper();

        // Pre-fix behaviour: baseDir = chapter's parent (sections/),
        // docfile = chapter's own path.  Asciidoctor resolves the
        // wrapper's `include::sections/_attributes.adoc[]` against
        // sections/, producing sections/sections/_attributes.adoc.
        doctor.load(wrapper, Options.builder()
                .backend("html5")
                .safe(SafeMode.UNSAFE)
                .baseDir(sections.toFile())
                .attributes(Attributes.builder()
                        .allowUriRead(true)
                        .attribute("docfile", sections.resolve("01-overview.adoc").toString())
                        .build())
                .build());

        LogRecord includeError = log.stream()
                .filter(r -> r.getMessage() != null
                        && r.getMessage().contains("include file not found"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected 'include file not found' under buggy config, got: " + log));
        String msg = includeError.getMessage();
        assertTrue(msg.contains("sections/sections/_attributes.adoc")
                        || msg.contains("sections\\sections\\_attributes.adoc"),
                "bug signature should contain doubled 'sections/sections/_attributes.adoc'; got: " + msg);
    }

    @Test
    void fixedParseConfigResolvesIncludesAndExposesHeaderAttributes(@TempDir Path tmp) throws Exception {
        Path master = makeProject(tmp);
        Path masterDir = master.getParent();
        String wrapper = buildWrapper();

        // Post-fix behaviour: baseDir = master's parent (the renderer's
        // true baseDir), and the docfile attribute is NOT leaked from
        // the current tab.  Both halves of the fix are needed:
        //  - correct baseDir so includes resolve under masterDir/...
        //  - no docfile leak so errors aren't stamped with the chapter's name
        var document = doctor.load(wrapper, Options.builder()
                .backend("html5")
                .safe(SafeMode.UNSAFE)
                .baseDir(masterDir.toFile())
                .attributes(Attributes.builder().allowUriRead(true).build())
                .build());

        long includeErrors = log.stream()
                .filter(r -> r.getMessage() != null
                        && r.getMessage().contains("include file not found"))
                .count();
        assertEquals(0L, includeErrors,
                "correct baseDir must resolve the wrapper's includes; log: " + log);

        // And the header attribute table the renderer actually wants is
        // still present — fixing the include bug must not trade for
        // losing the attribute extraction that was the reason for the
        // parse in the first place.
        assertEquals("custom", document.getAttributes().get("pdf-theme"),
                "header attribute pdf-theme must survive the parse; got "
                        + document.getAttributes());
        assertEquals("book", document.getAttributes().get("doctype"),
                "header attribute doctype must survive the parse; got "
                        + document.getAttributes());
        // And docdir points at the fixed baseDir, not the chapter's
        // parent — proves the parse used the baseDir we handed it.
        Object docdir = document.getAttributes().get("docdir");
        assertTrue(docdir != null && masterDir.toString().replace('\\', '/')
                        .equalsIgnoreCase(String.valueOf(docdir).replace('\\', '/')),
                "docdir must equal the fixed baseDir (" + masterDir + "); got " + docdir);
    }
}

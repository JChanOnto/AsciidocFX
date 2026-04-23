package com.kodedu.service.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PreviewSourceResolver}.
 *
 * <p>All scenarios use a temp project layout that mirrors the Operations
 * Guide: a master adoc that includes chapters from a {@code sections/} dir.
 */
class PreviewSourceResolverTest {

    @Test
    void fullScopeReturnsMasterUnchanged(@TempDir Path tmp) throws IOException {
        Path master = writeMaster(tmp, "= Book\n:doctype: book\n");
        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.FULL, master, null, null);
        assertEquals(PreviewScope.FULL, r.scopeUsed());
        assertEquals("= Book\n:doctype: book\n", r.source());
        assertEquals(tmp.toAbsolutePath().normalize(), r.baseDir());
        assertNull(r.notice());
    }

    @Test
    void fullScopePrefersLiveBufferWhenEditingMaster(@TempDir Path tmp) throws IOException {
        Path master = writeMaster(tmp, "= Old\n");
        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.FULL, master, master, "= Live\n");
        assertEquals("= Live\n", r.source());
    }

    @Test
    void chapterScopeFallsBackToFullWhenActiveFileIsNull(@TempDir Path tmp) throws IOException {
        Path master = writeMaster(tmp, "= Book\n");
        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, null, null);
        assertEquals(PreviewScope.FULL, r.scopeUsed());
    }

    @Test
    void chapterScopeFallsBackToFullWhenEditingMasterItself(@TempDir Path tmp) throws IOException {
        Path master = writeMaster(tmp, "= Book\n:doctype: book\n");
        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, master, "= Book\n:doctype: book\n");
        assertEquals(PreviewScope.FULL, r.scopeUsed());
    }

    @Test
    void chapterScopeSynthesizesMiniMasterForIncludedChapter(@TempDir Path tmp) throws IOException {
        Path chapter = writeChapter(tmp, "05-server-config.adoc", "== Server\n");
        Path master = writeMaster(tmp, "= Book\n:doctype: book\n\ninclude::sections/05-server-config.adoc[]\n");

        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, chapter, "== Server\n");

        assertEquals(PreviewScope.CHAPTER, r.scopeUsed());
        assertNull(r.notice());
        // Wrapper now preserves the master's header (so the chapter sees
        // the same attribute environment a full-master render would see —
        // critical for shared :diagram: macros, :imagesdir:, etc.) and
        // keeps the active chapter's include line.
        assertTrue(r.source().contains("include::sections/05-server-config.adoc[]"),
                () -> "expected active chapter include preserved, got:\n" + r.source());
        assertTrue(r.source().contains("= Book"),
                () -> "wrapper should preserve master title (suppressed at render via :notitle:):\n"
                        + r.source());
        assertTrue(r.source().contains(":doctype: book"));
        assertEquals(tmp.toAbsolutePath().normalize(), r.baseDir());
    }

    @Test
    void chapterScopeSuppressesTitlePageTocAndSectnums(@TempDir Path tmp) throws IOException {
        // Regression guard: chapter-mode previews must NOT inherit the
        // master's title page, TOC, or section numbering. These are the
        // standard asciidoctor-pdf opt-out attributes; the trailing `!`
        // is asciidoctor's "unset" syntax (overrides values turned on
        // elsewhere via .asciidoctorconfig or a parent doc).
        Path chapter = writeChapter(tmp, "01-overview.adoc", "== Overview\n");
        Path master = writeMaster(tmp,
                "= Book\n:doctype: book\n:toc: left\n:sectnums:\n\n"
                        + "include::sections/01-overview.adoc[]\n");

        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, chapter, "== Overview\n");

        assertEquals(PreviewScope.CHAPTER, r.scopeUsed());
        assertTrue(r.source().contains(":notitle:"),
                () -> "wrapper missing :notitle:\n" + r.source());
        assertTrue(r.source().contains(":title-page!:"),
                () -> "wrapper missing :title-page!:\n" + r.source());
        assertTrue(r.source().contains(":toc!:"),
                () -> "wrapper missing :toc!:\n" + r.source());
        assertTrue(r.source().contains(":sectnums!:"),
                () -> "wrapper missing :sectnums!:\n" + r.source());
    }

    @Test
    void fullScopeDoesNotSuppressTitlePageOrToc(@TempDir Path tmp) throws IOException {
        // Mirror of the above: master-scope renders MUST keep the
        // master's title page, TOC, and section numbering as authored.
        // The suppression attributes only ever live in the synthesized
        // chapter wrapper.
        Path master = writeMaster(tmp, "= Book\n:doctype: book\n:toc: left\n");
        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.FULL, master, null, null);

        assertFalse(r.source().contains(":notitle:"));
        assertFalse(r.source().contains(":title-page!:"));
        assertFalse(r.source().contains(":toc!:"));
        assertFalse(r.source().contains(":sectnums!:"));
    }

    @Test
    void chapterScopeFallsBackWithNoticeWhenChapterNotIncluded(@TempDir Path tmp) throws IOException {
        Path stray = writeChapter(tmp, "stray.adoc", "stray\n");
        Path master = writeMaster(tmp, "= Book\n");
        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, stray, "stray\n");

        assertEquals(PreviewScope.FULL, r.scopeUsed());
        assertNotNull(r.notice());
        assertTrue(r.notice().contains("not part of the master include chain"));
    }

    @Test
    void resolvesTransitiveIncludes(@TempDir Path tmp) throws IOException {
        Path leaf = writeChapter(tmp, "leaf.adoc", "leaf\n");
        // Hub file in sections/ that includes leaf
        Path hub = Files.writeString(
                Files.createDirectories(tmp.resolve("sections")).resolve("hub.adoc"),
                "include::leaf.adoc[]\n");
        Path master = writeMaster(tmp, "= Book\ninclude::sections/hub.adoc[]\n");

        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, leaf, "leaf\n");

        assertEquals(PreviewScope.CHAPTER, r.scopeUsed());
        assertTrue(r.source().contains("include::sections/leaf.adoc[]"),
                () -> "synthesized include should be relative to master:\n" + r.source());
    }

    @Test
    void chapterTitleStripsLeadingDigitsAndTitleCases() {
        assertEquals("Server Configuration",
                PreviewSourceResolver.chapterTitleFor(Path.of("05-server-configuration.adoc")));
        assertEquals("Knn Classification",
                PreviewSourceResolver.chapterTitleFor(Path.of("09_knn_classification.adoc")));
        assertEquals("Overview",
                PreviewSourceResolver.chapterTitleFor(Path.of("01-overview.adoc")));
        assertEquals("Chapter",
                PreviewSourceResolver.chapterTitleFor(Path.of("01-.adoc")));
    }

    @Test
    void resolvesNullScopeRejected(@TempDir Path tmp) throws IOException {
        Path master = writeMaster(tmp, "= Book\n");
        assertThrows(NullPointerException.class,
                () -> PreviewSourceResolver.resolve(null, master, null, null));
    }

    @Test
    void resolvesNullMasterRejected() {
        assertThrows(NullPointerException.class,
                () -> PreviewSourceResolver.resolve(PreviewScope.FULL, null, null, null));
    }

    private static Path writeMaster(Path projectDir, String body) throws IOException {
        return Files.writeString(projectDir.resolve("master.adoc"), body);
    }

    @Test
    void chapterWrapperPreservesPartialIncludesForSharedAttributes(@TempDir Path tmp) throws IOException {
        // Regression for the chapter-mode mermaid/image bug: shared
        // attribute partials (basename starts with `_`, the Asciidoctor
        // convention) define things like :diagram: and :imagesdir: that
        // chapter content references.  If the wrapper drops them, the
        // chapter renders with broken mermaid blocks and missing images.
        Path partial = writeChapter(tmp, "_attributes.adoc",
                ":diagram: mermaid,format=svg\n:imagesdir: screenshots\n");
        Path chapter = writeChapter(tmp, "01-overview.adoc",
                "== Overview\n\n[{diagram}]\n....\ngraph TD\nA-->B\n....\n");
        Path master = writeMaster(tmp,
                "= Book\n:doctype: book\n\n"
                        + "include::sections/_attributes.adoc[]\n"
                        + "include::sections/01-overview.adoc[]\n"
                        + "include::sections/02-other.adoc[]\n");

        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, chapter, "");

        assertEquals(PreviewScope.CHAPTER, r.scopeUsed());
        assertTrue(r.source().contains("include::sections/_attributes.adoc[]"),
                () -> "partial include must be preserved so chapter sees shared attrs:\n"
                        + r.source());
        assertTrue(r.source().contains("include::sections/01-overview.adoc[]"),
                () -> "active chapter include must be preserved:\n" + r.source());
    }

    @Test
    void chapterWrapperDropsSiblingChapterIncludes(@TempDir Path tmp) throws IOException {
        // The whole point of chapter mode is to render only the active
        // chapter for a fast preview.  Sibling chapter includes must be
        // stripped or chapter mode degenerates into full-master mode.
        Path active = writeChapter(tmp, "01-overview.adoc", "== Overview\n");
        writeChapter(tmp, "02-other.adoc", "== Other\n");
        writeChapter(tmp, "03-third.adoc", "== Third\n");
        Path master = writeMaster(tmp,
                "= Book\n:doctype: book\n\n"
                        + "include::sections/01-overview.adoc[]\n"
                        + "include::sections/02-other.adoc[]\n"
                        + "include::sections/03-third.adoc[]\n");

        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, active, "");

        assertTrue(r.source().contains("include::sections/01-overview.adoc[]"));
        assertFalse(r.source().contains("include::sections/02-other.adoc[]"),
                () -> "sibling chapter must be stripped:\n" + r.source());
        assertFalse(r.source().contains("include::sections/03-third.adoc[]"),
                () -> "sibling chapter must be stripped:\n" + r.source());
    }

    @Test
    void chapterWrapperAddsExplicitIncludeWhenChapterReachedTransitively(@TempDir Path tmp) throws IOException {
        // If the master does not directly include the active chapter
        // (e.g. it's reached via a hub file), strip the hub but emit an
        // explicit include of the active chapter so it still renders.
        Path leaf = writeChapter(tmp, "leaf.adoc", "leaf\n");
        Files.writeString(
                Files.createDirectories(tmp.resolve("sections")).resolve("hub.adoc"),
                "include::leaf.adoc[]\n");
        Path master = writeMaster(tmp, "= Book\n\ninclude::sections/hub.adoc[]\n");

        PreviewSourceResolver.Resolved r = PreviewSourceResolver.resolve(
                PreviewScope.CHAPTER, master, leaf, "leaf\n");

        assertEquals(PreviewScope.CHAPTER, r.scopeUsed());
        assertFalse(r.source().contains("include::sections/hub.adoc[]"),
                () -> "non-partial hub include must be stripped:\n" + r.source());
        assertTrue(r.source().contains("include::sections/leaf.adoc[]"),
                () -> "active chapter must be force-included when not at top level:\n"
                        + r.source());
    }

    private static Path writeChapter(Path projectDir, String name, String body) throws IOException {
        Path sections = Files.createDirectories(projectDir.resolve("sections"));
        return Files.writeString(sections.resolve(name), body);
    }
}

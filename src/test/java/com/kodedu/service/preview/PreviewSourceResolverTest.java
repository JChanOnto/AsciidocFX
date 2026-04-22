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
        assertTrue(r.source().contains("include::sections/05-server-config.adoc[]"),
                () -> "expected synthesized include, got:\n" + r.source());
        assertTrue(r.source().contains("Server Config (preview)"),
                () -> "expected derived chapter title, got:\n" + r.source());
        assertTrue(r.source().contains(":doctype: book"));
        assertEquals(tmp.toAbsolutePath().normalize(), r.baseDir());
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
    void includeExtractorReturnsOnlyTopOfLineIncludes(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("master.adoc"),
                String.join("\n",
                        "= Book",
                        "include::sections/01.adoc[]",
                        "  include::sections/02.adoc[]",
                        "// include::sections/comment.adoc[]",  // commented; conservative parser still picks up — see note
                        "include::sections/03.adoc[leveloffset=+1]",
                        "Inline include::sections/inline.adoc[] in prose")
        );
        var includes = PreviewSourceResolver.extractIncludes(file);
        // Top-of-line and indented forms count; commented and inline-in-prose do not.
        assertTrue(includes.contains("sections/01.adoc"));
        assertTrue(includes.contains("sections/02.adoc"));
        assertTrue(includes.contains("sections/03.adoc"));
        assertFalse(includes.contains("sections/inline.adoc"));
        // Comment lines: the resolver doesn't try to parse comments; they are
        // simply not matched because '//' doesn't satisfy the regex anchor.
        assertFalse(includes.contains("sections/comment.adoc"));
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

    private static Path writeChapter(Path projectDir, String name, String body) throws IOException {
        Path sections = Files.createDirectories(projectDir.resolve("sections"));
        return Files.writeString(sections.resolve(name), body);
    }
}

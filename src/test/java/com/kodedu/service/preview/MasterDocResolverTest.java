package com.kodedu.service.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MasterDocResolver} — the BFS over the
 * {@code include::} graph that powers the file-tree MASTER badge and the
 * multi-book disambiguation dialog.
 */
class MasterDocResolverTest {

    @Test
    void findReferrersReturnsEmptyForUnknownProject(@TempDir Path tmp) {
        Path stray = tmp.resolve("nope.adoc");
        assertTrue(MasterDocResolver.findReferrers(tmp, stray).isEmpty());
    }

    @Test
    void findReferrersReturnsSingleParentForChapter(@TempDir Path tmp) throws IOException {
        Path chapter = writeChapter(tmp, "01.adoc", "= Chapter 1\n");
        Path master = writeMaster(tmp, "book.adoc", "= Book\ninclude::sections/01.adoc[]\n");

        List<Path> referrers = MasterDocResolver.findReferrers(tmp, chapter);
        assertEquals(1, referrers.size());
        assertEquals(master.toAbsolutePath().normalize(), referrers.get(0).toAbsolutePath().normalize());
    }

    @Test
    void findReferrersReturnsAllBooksThatIncludeSharedChapter(@TempDir Path tmp) throws IOException {
        // "Book of books" pattern: shared chapter included by two
        // top-level masters. Disambiguation dialog needs both surfaced.
        Path shared = writeChapter(tmp, "shared.adoc", "= Shared\n");
        Path book1 = writeMaster(tmp, "book-1.adoc", "= Book 1\ninclude::sections/shared.adoc[]\n");
        Path book2 = writeMaster(tmp, "book-2.adoc", "= Book 2\ninclude::sections/shared.adoc[]\n");
        Path orphanBook = writeMaster(tmp, "book-3.adoc", "= Book 3 standalone\n");

        List<Path> referrers = MasterDocResolver.findReferrers(tmp, shared);
        assertEquals(2, referrers.size());
        assertTrue(referrers.contains(book1.toAbsolutePath().normalize()));
        assertTrue(referrers.contains(book2.toAbsolutePath().normalize()));
        assertFalse(referrers.contains(orphanBook.toAbsolutePath().normalize()));
    }

    @Test
    void findReferrersWalksTransitiveIncludes(@TempDir Path tmp) throws IOException {
        // master -> hub -> leaf : the leaf's referrer is the master.
        Path leaf = writeChapter(tmp, "leaf.adoc", "= Leaf\n");
        Files.writeString(tmp.resolve("sections").resolve("hub.adoc"),
                "include::leaf.adoc[]\n");
        Path master = writeMaster(tmp, "book.adoc", "= Book\ninclude::sections/hub.adoc[]\n");

        List<Path> referrers = MasterDocResolver.findReferrers(tmp, leaf);
        assertEquals(1, referrers.size());
        assertEquals(master.toAbsolutePath().normalize(), referrers.get(0).toAbsolutePath().normalize());
    }

    @Test
    void findReferrersTopLevelAdocAppearsAsItsOwnReferrer(@TempDir Path tmp) throws IOException {
        // No other book includes this top-level adoc, but the BFS still
        // reaches it (depth=0, start node) so it surfaces as its own
        // referrer. This is what lets the file-tree badge a standalone
        // book correctly.
        Path lonely = writeMaster(tmp, "lonely.adoc", "= Lonely standalone\n");
        List<Path> referrers = MasterDocResolver.findReferrers(tmp, lonely);
        assertEquals(1, referrers.size());
        assertEquals(lonely.toAbsolutePath().normalize(), referrers.get(0).toAbsolutePath().normalize());
    }

    @Test
    void isReachableHandlesCycles(@TempDir Path tmp) throws IOException {
        // a includes b, b includes a — must not hang.
        Path a = Files.writeString(tmp.resolve("a.adoc"), "include::b.adoc[]\n");
        Files.writeString(tmp.resolve("b.adoc"), "include::a.adoc[]\n");
        Path standalone = Files.writeString(tmp.resolve("c.adoc"), "= C\n");

        assertTrue(MasterDocResolver.isReachable(a, a),
                "self-reachable via include cycle");
        assertFalse(MasterDocResolver.isReachable(a, standalone),
                "unrelated file is not reachable");
    }

    @Test
    void isReachableTrueForStartNode(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("solo.adoc"), "= Solo\n");
        assertTrue(MasterDocResolver.isReachable(file, file));
    }

    @Test
    void isReachableNullsAreFalse(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("solo.adoc"), "= Solo\n");
        assertFalse(MasterDocResolver.isReachable(null, file));
        assertFalse(MasterDocResolver.isReachable(file, null));
        assertFalse(MasterDocResolver.isReachable(null, null));
    }

    @Test
    void findReferrersHandlesUnresolvedAttributeReferences(@TempDir Path tmp) throws IOException {
        // include::{undefined-attr}/x.adoc[] — resolver must skip rather
        // than crash or false-positive.
        Path chapter = writeChapter(tmp, "real.adoc", "= Real\n");
        Path master = writeMaster(tmp, "book.adoc",
                "= Book\n"
                        + "include::{undefined-attr}/missing.adoc[]\n"
                        + "include::sections/real.adoc[]\n");

        List<Path> referrers = MasterDocResolver.findReferrers(tmp, chapter);
        assertEquals(1, referrers.size());
        assertEquals(master.toAbsolutePath().normalize(), referrers.get(0).toAbsolutePath().normalize());
    }

    private static Path writeMaster(Path projectDir, String name, String body) throws IOException {
        return Files.writeString(projectDir.resolve(name), body);
    }

    private static Path writeChapter(Path projectDir, String name, String body) throws IOException {
        Path sections = Files.createDirectories(projectDir.resolve("sections"));
        return Files.writeString(sections.resolve(name), body);
    }
}

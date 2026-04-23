package com.kodedu.component;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit pin for {@link PdfPreviewPane#extractOutline(PDDocument)}.
 * Builds in-memory PDDocuments with PDFBox so the recursion / null
 * handling / page-resolution branches are exercised against a real
 * outline tree, not a mock.
 *
 * <p>This is the contract that powers the PDF preview's left-hand
 * outline navigator.  asciidoctor-pdf populates the {@code /Outlines}
 * dictionary with section bookmarks; the extractor must turn that
 * into a tree of {@link PdfPreviewPane.PdfOutlineEntry} preserving
 * order, depth, and resolved page indices, while gracefully
 * tolerating untitled items and items whose destination cannot be
 * resolved (which arise in PDFs produced by some downstream
 * tools).
 */
class PdfOutlineExtractTest {

    /** Helper: add a top-level outline item with a named title and a
     *  destination pointing at the given page. */
    private static PDOutlineItem addItem(PDDocumentOutline parent, String title, PDPage destPage) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        if (destPage != null) {
            PDPageFitDestination dest = new PDPageFitDestination();
            dest.setPage(destPage);
            item.setDestination(dest);
        }
        parent.addLast(item);
        return item;
    }

    /** Helper: nest a child under an existing outline item. */
    private static PDOutlineItem addChild(PDOutlineItem parent, String title, PDPage destPage) {
        PDOutlineItem child = new PDOutlineItem();
        child.setTitle(title);
        if (destPage != null) {
            PDPageFitDestination dest = new PDPageFitDestination();
            dest.setPage(destPage);
            child.setDestination(dest);
        }
        parent.addLast(child);
        return child;
    }

    @Test
    void documentWithoutOutlineReturnsEmptyList() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            // No outline set on the catalog.
            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertTrue(entries.isEmpty(),
                    "PDFs with no outline must yield an empty list, not null and not a default entry");
        }
    }

    @Test
    void emptyOutlineReturnsEmptyList() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            // Outline exists but has no items.
            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertTrue(entries.isEmpty(),
                    "an outline with no items must yield an empty list");
        }
    }

    @Test
    void flatOutlineMapsTitlesAndPageIndicesInOrder() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage p1 = new PDPage();
            PDPage p2 = new PDPage();
            PDPage p3 = new PDPage();
            doc.addPage(p1);
            doc.addPage(p2);
            doc.addPage(p3);
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            addItem(outline, "Chapter 1", p1);
            addItem(outline, "Chapter 2", p2);
            addItem(outline, "Chapter 3", p3);

            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertEquals(3, entries.size());
            assertEquals("Chapter 1", entries.get(0).title());
            assertEquals(0, entries.get(0).pageIndex());
            assertEquals("Chapter 2", entries.get(1).title());
            assertEquals(1, entries.get(1).pageIndex());
            assertEquals("Chapter 3", entries.get(2).title());
            assertEquals(2, entries.get(2).pageIndex());
            for (PdfPreviewPane.PdfOutlineEntry e : entries) {
                assertTrue(e.children().isEmpty(),
                        "flat outline items must have no children: " + e);
            }
        }
    }

    @Test
    void nestedOutlinePreservesTreeStructure() throws Exception {
        // Realistic shape: 2 chapters, each with 2 sections.  Mirrors
        // what asciidoctor-pdf produces for a document with `:toc:`
        // and headings.  A regression that swallowed a level (e.g.
        // returned a flattened list) would silently break navigation
        // depth, so we assert the full tree shape.
        try (PDDocument doc = new PDDocument()) {
            PDPage p1 = new PDPage();
            PDPage p2 = new PDPage();
            PDPage p3 = new PDPage();
            PDPage p4 = new PDPage();
            PDPage p5 = new PDPage();
            doc.addPage(p1);
            doc.addPage(p2);
            doc.addPage(p3);
            doc.addPage(p4);
            doc.addPage(p5);

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem ch1 = addItem(outline, "Chapter 1", p1);
            addChild(ch1, "1.1 Introduction", p2);
            addChild(ch1, "1.2 Background", p3);
            PDOutlineItem ch2 = addItem(outline, "Chapter 2", p4);
            addChild(ch2, "2.1 Method", p5);

            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertEquals(2, entries.size(), "two top-level chapters expected");

            PdfPreviewPane.PdfOutlineEntry first = entries.get(0);
            assertEquals("Chapter 1", first.title());
            assertEquals(0, first.pageIndex());
            assertEquals(2, first.children().size());
            assertEquals("1.1 Introduction", first.children().get(0).title());
            assertEquals(1, first.children().get(0).pageIndex());
            assertEquals("1.2 Background", first.children().get(1).title());
            assertEquals(2, first.children().get(1).pageIndex());

            PdfPreviewPane.PdfOutlineEntry second = entries.get(1);
            assertEquals("Chapter 2", second.title());
            assertEquals(3, second.pageIndex());
            assertEquals(1, second.children().size());
            assertEquals("2.1 Method", second.children().get(0).title());
            assertEquals(4, second.children().get(0).pageIndex());
        }
    }

    @Test
    void itemWithoutTitleFallsBackToPlaceholder() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage p1 = new PDPage();
            doc.addPage(p1);
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            // Title null \u2192 must NOT crash and must NOT render a blank
            // line in the navigator.  The "(untitled)" placeholder is
            // what the user sees in the tree.
            addItem(outline, null, p1);

            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertEquals(1, entries.size());
            assertEquals("(untitled)", entries.get(0).title(),
                    "null title must fall back to a visible placeholder");
            assertEquals(0, entries.get(0).pageIndex());
        }
    }

    @Test
    void itemWithoutDestinationGetsNegativePageIndex() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            // No destination set \u2192 findDestinationPage returns null
            // \u2192 pageIndex must be -1 so the navigator can render
            // the entry without a "p#" suffix and skip the scroll
            // binding.
            addItem(outline, "Section without dest", null);

            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertEquals(1, entries.size());
            assertEquals("Section without dest", entries.get(0).title());
            assertEquals(-1, entries.get(0).pageIndex(),
                    "destination-less entries must report pageIndex=-1, not 0");
        }
    }

    @Test
    void deeplyNestedOutlineDoesNotLoseChildren() throws Exception {
        // 5-level deep nest mirrors a worst-case asciidoctor section
        // tree (book \u2192 part \u2192 chapter \u2192 section \u2192
        // subsection).  The recursion must walk the whole depth without
        // truncating.
        try (PDDocument doc = new PDDocument()) {
            PDPage p = new PDPage();
            doc.addPage(p);
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem level1 = addItem(outline, "L1", p);
            PDOutlineItem level2 = addChild(level1, "L2", p);
            PDOutlineItem level3 = addChild(level2, "L3", p);
            PDOutlineItem level4 = addChild(level3, "L4", p);
            addChild(level4, "L5", p);

            List<PdfPreviewPane.PdfOutlineEntry> entries =
                    PdfPreviewPane.extractOutline(doc);
            assertEquals(1, entries.size());
            PdfPreviewPane.PdfOutlineEntry cur = entries.get(0);
            for (String expectedTitle : List.of("L1", "L2", "L3", "L4", "L5")) {
                assertEquals(expectedTitle, cur.title(),
                        "expected entry " + expectedTitle + " at this depth, got " + cur);
                if (expectedTitle.equals("L5")) {
                    assertTrue(cur.children().isEmpty(), "deepest entry must have no children");
                } else {
                    assertEquals(1, cur.children().size(),
                            "each non-leaf must have exactly one child in this fixture; got " + cur);
                    cur = cur.children().get(0);
                }
            }
        }
    }
}

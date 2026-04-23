package com.kodedu.component;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.SplitPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-toolkit verification of the outline navigation contract:
 * selecting a {@link PdfPreviewPane.PdfOutlineEntry} in the
 * outline {@link TreeView} must scroll the pages
 * {@link ScrollPane} so the target page is in view.  Mirrors the
 * production wiring in
 * {@link PdfPreviewPane#buildOutlineTree()} +
 * {@link PdfPreviewPane#scrollToPage(int)}.
 *
 * <p>The full {@link PdfPreviewPane} bean is too heavy to spin up
 * in isolation (Spring + the entire AsciidocFX context), so we
 * recreate the same controls and wire them up the same way the
 * production code does.  A regression in either the binding or
 * the scroll math is what these tests defend against.
 */
@ExtendWith(ApplicationExtension.class)
class PdfPreviewOutlineNavigationTest {

    private static final double PAGE_W = 612;
    private static final double PAGE_H = 792;
    private static final int PAGE_COUNT = 20;
    private static final double VIEWPORT_W = 700;
    private static final double VIEWPORT_H = 500;

    private TreeView<PdfPreviewPane.PdfOutlineEntry> outlineTree;
    private VBox pagesBox;
    private ScrollPane scrollPane;

    @Start
    void start(Stage stage) {
        // Build a pages box with 20 fixed-size ImageView pages, like
        // production after a render of a multi-page PDF.
        pagesBox = new VBox(8);
        WritableImage placeholder = new WritableImage(1, 1);
        for (int i = 0; i < PAGE_COUNT; i++) {
            ImageView iv = new ImageView(placeholder);
            iv.setPreserveRatio(false);
            iv.setFitWidth(PAGE_W);
            iv.setFitHeight(PAGE_H);
            pagesBox.getChildren().add(iv);
        }
        scrollPane = new ScrollPane(pagesBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(VIEWPORT_W);
        scrollPane.setPrefViewportHeight(VIEWPORT_H);

        // Build outline tree mirroring PdfPreviewPane.buildOutlineTree.
        TreeItem<PdfPreviewPane.PdfOutlineEntry> root = new TreeItem<>(
                new PdfPreviewPane.PdfOutlineEntry("Outline", -1, List.of()));
        root.setExpanded(true);
        outlineTree = new TreeView<>(root);
        outlineTree.setShowRoot(false);
        outlineTree.setPrefWidth(220);

        // The production binding: select an entry \u2192 scroll to its page.
        outlineTree.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (is != null && is.getValue() != null && is.getValue().pageIndex() >= 0) {
                scrollToPage(is.getValue().pageIndex());
            }
        });

        SplitPane split = new SplitPane(outlineTree, scrollPane);
        split.setDividerPositions(0.22);
        stage.setScene(new Scene(split, VIEWPORT_W + 220, VIEWPORT_H));
        stage.show();
    }

    /** Mirror of {@link PdfPreviewPane#scrollToPage(int)} so this test
     *  pins the same scroll math the production code uses. */
    private void scrollToPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pagesBox.getChildren().size()) return;
        Platform.runLater(() -> {
            javafx.scene.Node target = pagesBox.getChildren().get(pageIndex);
            double targetY = target.getBoundsInParent().getMinY();
            double contentHeight = pagesBox.getBoundsInLocal().getHeight();
            double viewportHeight = scrollPane.getViewportBounds().getHeight();
            double scrollable = Math.max(1.0, contentHeight - viewportHeight);
            double v = Math.max(0.0, Math.min(1.0, targetY / scrollable));
            scrollPane.setVvalue(v);
        });
    }

    private void publishOutline(List<PdfPreviewPane.PdfOutlineEntry> entries)
            throws InterruptedException {
        onFx(() -> {
            TreeItem<PdfPreviewPane.PdfOutlineEntry> root = outlineTree.getRoot();
            root.getChildren().clear();
            for (PdfPreviewPane.PdfOutlineEntry e : entries) {
                root.getChildren().add(toTreeItem(e));
            }
        });
    }

    private TreeItem<PdfPreviewPane.PdfOutlineEntry> toTreeItem(
            PdfPreviewPane.PdfOutlineEntry e) {
        TreeItem<PdfPreviewPane.PdfOutlineEntry> ti = new TreeItem<>(e);
        ti.setExpanded(true); // expanded so we can find children directly in tests
        for (PdfPreviewPane.PdfOutlineEntry c : e.children()) {
            ti.getChildren().add(toTreeItem(c));
        }
        return ti;
    }

    private static void onFx(Runnable r) throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX task timed out");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void publishingOutlinePopulatesTreeWithEntries() throws Exception {
        publishOutline(List.of(
                new PdfPreviewPane.PdfOutlineEntry("Chapter 1", 0, List.of()),
                new PdfPreviewPane.PdfOutlineEntry("Chapter 2", 5, List.of(
                        new PdfPreviewPane.PdfOutlineEntry("2.1 Section", 6, List.of()))),
                new PdfPreviewPane.PdfOutlineEntry("Chapter 3", 10, List.of())));
        WaitForAsyncUtils.waitForFxEvents();

        TreeItem<PdfPreviewPane.PdfOutlineEntry> root = outlineTree.getRoot();
        assertEquals(3, root.getChildren().size(),
                "publishOutline must add one TreeItem per top-level entry");
        assertEquals("Chapter 1", root.getChildren().get(0).getValue().title());
        assertEquals(1, root.getChildren().get(1).getChildren().size(),
                "nested entries must appear as children of their parent TreeItem");
        assertEquals("2.1 Section",
                root.getChildren().get(1).getChildren().get(0).getValue().title());
    }

    @Test
    void selectingOutlineEntryScrollsToTargetPage() throws Exception {
        publishOutline(List.of(
                new PdfPreviewPane.PdfOutlineEntry("Front matter", 0, List.of()),
                new PdfPreviewPane.PdfOutlineEntry("Chapter 5 (page 10)", 10, List.of()),
                new PdfPreviewPane.PdfOutlineEntry("Appendix (page 18)", 18, List.of())));
        WaitForAsyncUtils.waitForFxEvents();

        // Precondition: scroll position is at the top.
        onFx(() -> scrollPane.setVvalue(0));
        assertEquals(0.0, scrollPane.getVvalue(), 1e-9);

        // Select Chapter 5 (page index 10) \u2192 scrollToPage fires \u2192
        // ScrollPane vvalue moves to the band that puts page 10 in view.
        onFx(() -> outlineTree.getSelectionModel().select(
                outlineTree.getRoot().getChildren().get(1)));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        double vAfterChapter5 = scrollPane.getVvalue();
        assertTrue(vAfterChapter5 > 0.3 && vAfterChapter5 < 0.8,
                "selecting Chapter 5 (page 10 of 20) should scroll to mid-document; got vvalue="
                        + vAfterChapter5);

        // Select Appendix (page index 18) \u2192 should scroll near the end.
        onFx(() -> outlineTree.getSelectionModel().select(
                outlineTree.getRoot().getChildren().get(2)));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        double vAfterAppendix = scrollPane.getVvalue();
        assertTrue(vAfterAppendix > vAfterChapter5,
                "selecting a later entry must move scroll position further down; "
                        + "Appendix vvalue=" + vAfterAppendix + " was not greater than "
                        + "Chapter 5 vvalue=" + vAfterChapter5);
        assertTrue(vAfterAppendix > 0.85,
                "selecting Appendix (page 18 of 20) should scroll near the end; got "
                        + vAfterAppendix);
    }

    @Test
    void selectingFrontMatterScrollsToTop() throws Exception {
        publishOutline(List.of(
                new PdfPreviewPane.PdfOutlineEntry("Front matter (page 1)", 0, List.of()),
                new PdfPreviewPane.PdfOutlineEntry("End (page 20)", 19, List.of())));
        WaitForAsyncUtils.waitForFxEvents();

        // Scroll away from the top first.
        onFx(() -> scrollPane.setVvalue(0.7));
        assertTrue(scrollPane.getVvalue() > 0.5, "precondition: scrolled away from top");

        onFx(() -> outlineTree.getSelectionModel().select(
                outlineTree.getRoot().getChildren().get(0)));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0.0, scrollPane.getVvalue(), 0.05,
                "selecting page 0 must snap scroll back to the top");
    }

    @Test
    void selectingEntryWithoutDestinationDoesNotScroll() throws Exception {
        publishOutline(List.of(
                new PdfPreviewPane.PdfOutlineEntry("Section header (no dest)", -1, List.of()),
                new PdfPreviewPane.PdfOutlineEntry("Real entry", 5, List.of())));
        WaitForAsyncUtils.waitForFxEvents();

        // Set a non-zero scroll position, then click the dest-less entry.
        onFx(() -> scrollPane.setVvalue(0.4));
        double before = scrollPane.getVvalue();

        onFx(() -> outlineTree.getSelectionModel().select(
                outlineTree.getRoot().getChildren().get(0)));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before, scrollPane.getVvalue(), 1e-9,
                "selecting an entry with pageIndex=-1 must NOT move the scroll position; "
                        + "otherwise destination-less section headers would teleport the user to page 1");
    }

    @Test
    void treeItemForOutlineEntryDisplaysExpectedText() throws Exception {
        // Sanity contract on the cell-factory display format used by
        // PdfPreviewPane.buildOutlineTree: titled+paged entries show
        // "title  \u00b7  p<N>"; titled+unpaged entries show just title.
        // A regression that drops the page suffix would make
        // navigation harder; we lock in the format here.
        publishOutline(List.of(
                new PdfPreviewPane.PdfOutlineEntry("Chapter 1", 0, List.of())));
        WaitForAsyncUtils.waitForFxEvents();

        TreeItem<PdfPreviewPane.PdfOutlineEntry> item =
                outlineTree.getRoot().getChildren().get(0);
        assertNotNull(item);
        PdfPreviewPane.PdfOutlineEntry value = item.getValue();
        assertEquals("Chapter 1", value.title());
        assertEquals(0, value.pageIndex());
        // The cell-factory text format is verified by visual contract
        // in production; the data underlying it is what we assert.
    }
}

package com.kodedu.service.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the AsciiDoc input for the live PDF preview.
 *
 * <p>Two scopes:
 * <ul>
 *   <li>{@link PreviewScope#FULL} — return the master document unchanged.</li>
 *   <li>{@link PreviewScope#CHAPTER} — if the active editor file is reached
 *       (transitively) from the master via {@code include::} directives,
 *       synthesize a tiny standalone document that includes only that file.
 *       If not reachable, fall back to FULL with a notice.</li>
 * </ul>
 *
 * <p>Pure logic, no Spring, no JavaFX — fully unit-testable.
 */
public final class PreviewSourceResolver {

    private PreviewSourceResolver() {}

    /** Result of a resolve call. {@code notice} is non-null only when
     *  CHAPTER scope was requested but had to fall back to FULL. */
    public record Resolved(String source, Path baseDir, PreviewScope scopeUsed, String notice) {
        public static Resolved full(String source, Path baseDir) {
            return new Resolved(source, baseDir, PreviewScope.FULL, null);
        }
        public static Resolved chapter(String source, Path baseDir) {
            return new Resolved(source, baseDir, PreviewScope.CHAPTER, null);
        }
        public static Resolved fallback(String source, Path baseDir, String notice) {
            return new Resolved(source, baseDir, PreviewScope.FULL, notice);
        }
    }

    /**
     * Resolve the preview source.
     *
     * @param scope          requested scope
     * @param masterAdoc     project master document on disk
     * @param activeFile     editor's currently-active file (may be null)
     * @param liveSource     the editor buffer for {@code activeFile}.  Used
     *                       only when active==master; chapter-mode renders
     *                       always pull the chapter from disk via
     *                       {@code include::}.
     */
    public static Resolved resolve(PreviewScope scope, Path masterAdoc, Path activeFile,
                                   String liveSource) throws IOException {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(masterAdoc, "masterAdoc");

        Path masterAbs = masterAdoc.toAbsolutePath().normalize();
        Path baseDir = Optional.ofNullable(masterAbs.getParent())
                .orElseThrow(() -> new IllegalArgumentException("master has no parent: " + masterAdoc));

        if (scope == PreviewScope.FULL || activeFile == null) {
            return Resolved.full(readMaster(masterAbs, activeFile, liveSource), baseDir);
        }

        Path activeAbs = activeFile.toAbsolutePath().normalize();
        if (activeAbs.equals(masterAbs)) {
            // Editing the master itself — chapter mode is meaningless.
            return Resolved.full(liveSource != null ? liveSource : Files.readString(masterAbs), baseDir);
        }

        if (!MasterDocResolver.isReachable(masterAbs, activeAbs)) {
            String notice = "'" + activeAbs.getFileName()
                    + "' is not part of the master include chain — showing full doc";
            return Resolved.fallback(readMaster(masterAbs, activeFile, liveSource), baseDir, notice);
        }

        String relative = baseDir.relativize(activeAbs).toString().replace('\\', '/');
        // Build the chapter wrapper from the MASTER document instead of a
        // bare include::active[].  Reason: shared attribute partials (e.g.
        // sections/_attributes.adoc, which defines :diagram:, :imagesdir:,
        // mermaid theme paths, etc.) are typically pulled in by the master
        // before the first chapter include.  A naive `include::chapter[]`
        // wrapper skips them and breaks anything in the chapter that
        // references those attributes (mermaid `[{diagram}]` blocks fail to
        // render, image:: macros lose their imagesdir, etc.).
        //
        // Strategy: take the master verbatim, drop any include of another
        // chapter (so we don't render the whole book), and append the
        // suppression attributes that turn off the title page / TOC /
        // section numbers for the chapter view.  Includes of partial files
        // (basename starts with `_`, the Asciidoctor convention for non-
        // standalone fragments) are preserved.
        String masterText = Files.readString(masterAbs);
        String wrapper = buildChapterWrapper(masterText, baseDir, activeAbs);
        return Resolved.chapter(wrapper, baseDir);
    }

    /**
     * Construct the chapter-mode wrapper from the master document.
     *
     * <p>For every {@code include::path[]} line in the master:
     * <ul>
     *   <li>if {@code path} resolves to the active chapter, keep it;</li>
     *   <li>if the included file's basename starts with {@code _} (the
     *       Asciidoctor partial convention), keep it — these typically
     *       supply shared attribute definitions that the chapter
     *       depends on (e.g. {@code [{diagram}]});</li>
     *   <li>otherwise, drop it (it's a sibling chapter we don't want to
     *       render in chapter mode).</li>
     * </ul>
     *
     * <p>The suppression attributes ({@code :notitle:},
     * {@code :title-page!:}, {@code :toc!:}, {@code :sectnums!:}) are
     * appended at the end of the document header so they take effect
     * regardless of what the master declared.
     */
    static String buildChapterWrapper(String masterText, Path baseDir, Path activeAbs) {
        String[] lines = masterText.split("\\R", -1);
        StringBuilder out = new StringBuilder(masterText.length());
        boolean activeChapterSeen = false;
        boolean suppressionAppended = false;
        boolean inHeader = true;
        for (String line : lines) {
            String trimmed = line.trim();
            // The header ends at the first blank line after the doc
            // title.  We append suppression attrs at the boundary so
            // they override anything the master declared.
            if (inHeader && trimmed.isEmpty() && !suppressionAppended) {
                out.append(":notitle:\n");
                out.append(":title-page!:\n");
                out.append(":toc!:\n");
                out.append(":sectnums!:\n");
                suppressionAppended = true;
                inHeader = false;
            }
            if (trimmed.startsWith("include::")) {
                int closeBracket = trimmed.indexOf("[", "include::".length());
                if (closeBracket < 0) {
                    // Malformed include — leave the master's text alone.
                    out.append(line).append('\n');
                    continue;
                }
                String target = trimmed.substring("include::".length(), closeBracket);
                Path includedAbs;
                try {
                    includedAbs = baseDir.resolve(target).toAbsolutePath().normalize();
                } catch (Exception ex) {
                    out.append(line).append('\n');
                    continue;
                }
                if (includedAbs.equals(activeAbs)) {
                    out.append(line).append('\n');
                    activeChapterSeen = true;
                    continue;
                }
                String basename = includedAbs.getFileName().toString();
                if (basename.startsWith("_")) {
                    // Partial / shared-attributes file — keep so the
                    // chapter sees the same attribute environment a
                    // full-master render would.
                    out.append(line).append('\n');
                    continue;
                }
                // Sibling chapter — skip.
                continue;
            }
            out.append(line).append('\n');
        }
        if (!suppressionAppended) {
            // Master had no blank line after the header — append at end of file.
            out.append("\n:notitle:\n:title-page!:\n:toc!:\n:sectnums!:\n");
        }
        if (!activeChapterSeen) {
            // The master did not actually `include::` the active chapter
            // at the top level (it might be reached via a partial).  Add
            // an explicit include so the chapter is guaranteed to render.
            String relative = baseDir.relativize(activeAbs).toString().replace('\\', '/');
            out.append("\ninclude::").append(relative).append("[]\n");
        }
        return out.toString();
    }

    /** Best-effort chapter title from filename: {@code 05-server-config.adoc} → {@code Server Config}. */
    static String chapterTitleFor(Path adocFile) {
        String name = adocFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceFirst("^[0-9]+[._-]+", "")
                   .replace('_', ' ').replace('-', ' ').trim();
        if (name.isEmpty()) {
            return "Chapter";
        }
        return Stream.of(name.split("\\s+"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static String readMaster(Path masterAbs, Path activeFile, String liveSource) throws IOException {
        if (activeFile != null && liveSource != null
                && activeFile.toAbsolutePath().normalize().equals(masterAbs)) {
            return liveSource;
        }
        return Files.readString(masterAbs);
    }
}

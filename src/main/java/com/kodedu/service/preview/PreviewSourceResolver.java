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
        String synthesized = ""
                + "= " + chapterTitleFor(activeAbs) + " (preview)\n"
                + ":doctype: book\n"
                + "include::" + relative + "[]\n";
        return Resolved.chapter(synthesized, baseDir);
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

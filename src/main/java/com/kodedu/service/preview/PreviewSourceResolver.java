package com.kodedu.service.preview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *       synthesize a tiny standalone document that includes only that file
 *       (rendering ~5–20 pages instead of the full book).  If not reachable,
 *       fall back to FULL with a notice.</li>
 * </ul>
 *
 * <p>Pure logic, no Spring, no JavaFX — fully unit-testable.
 */
public final class PreviewSourceResolver {

    private static final Logger logger = LoggerFactory.getLogger(PreviewSourceResolver.class);

    /**
     * Captures {@code include::path[opts]} (top of line, ignores leading
     * whitespace).  Group 1 is the include target.  Comments ({@code //…})
     * and content inside literal blocks are not stripped — we only walk
     * top-level structure for chapter discovery, which is robust for the
     * common book-style master adoc pattern.
     */
    private static final Pattern INCLUDE_LINE = Pattern.compile(
            "^\\s*include::([^\\[\\s]+)\\[[^\\]]*\\]\\s*$");

    /** Soft cap on transitive include traversal to bound resolver cost. */
    private static final int MAX_INCLUDES = 500;

    private PreviewSourceResolver() {}

    /**
     * Result of a resolve call.  {@code source} is the AsciiDoc text to feed
     * the renderer; {@code baseDir} is the resource resolution root.
     * {@code notice} is non-null when the resolver had to fall back from
     * chapter to full mode.
     */
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
     * @param masterAdoc     project master document on disk (e.g.
     *                       {@code TrueADC_3.11_Operations_Guide.adoc}).  May
     *                       be the same as {@code activeFile} if the user is
     *                       editing the master directly.
     * @param activeFile     the editor's currently-active file (may be null
     *                       when no file-backed tab is selected)
     * @param liveSource     the editor buffer for {@code activeFile}.  If the
     *                       resolver synthesizes a mini-master, the chapter's
     *                       on-disk file is included and the unsaved buffer
     *                       is intentionally not used (saves are the trigger
     *                       in normal flow).
     * @return the AsciiDoc source + base directory + actual scope used
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
            // Editing the master itself — chapter mode is meaningless; render full.
            return Resolved.full(liveSource != null ? liveSource : readString(masterAbs), baseDir);
        }

        if (!isReachableInclude(masterAbs, activeAbs)) {
            String notice = "'" + masterAbs.relativize(activeAbs).getFileName()
                    + "' is not part of the master include chain — showing full doc";
            return Resolved.fallback(readMaster(masterAbs, activeFile, liveSource), baseDir, notice);
        }

        String relative = posix(baseDir.relativize(activeAbs));
        String synthesized = ""
                + "= " + chapterTitleFor(activeAbs) + " (preview)\n"
                + ":doctype: book\n"
                + "include::" + relative + "[]\n";
        return Resolved.chapter(synthesized, baseDir);
    }

    /**
     * @return true iff {@code candidate} is reachable from {@code master} via
     *         transitive {@code include::} directives (top-of-line form)
     */
    static boolean isReachableInclude(Path master, Path candidate) throws IOException {
        Path masterAbs = master.toAbsolutePath().normalize();
        Path candidateAbs = candidate.toAbsolutePath().normalize();
        Set<Path> visited = new HashSet<>();
        List<Path> stack = new ArrayList<>();
        stack.add(masterAbs);

        int budget = MAX_INCLUDES;
        while (!stack.isEmpty() && budget-- > 0) {
            Path file = stack.remove(stack.size() - 1);
            if (!visited.add(file) || !Files.isRegularFile(file)) {
                continue;
            }
            for (String include : extractIncludes(file)) {
                Path target = file.getParent().resolve(include).toAbsolutePath().normalize();
                if (target.equals(candidateAbs)) {
                    return true;
                }
                if (Files.isRegularFile(target) && isAdocLike(target)) {
                    stack.add(target);
                }
            }
        }
        return false;
    }

    /** Best-effort chapter title from filename: {@code 05-server-config.adoc} → {@code Server Config}. */
    static String chapterTitleFor(Path adocFile) {
        String name = adocFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        // Strip leading digits + separator (e.g. "05-")
        name = name.replaceFirst("^[0-9]+[._-]+", "");
        // Replace separators with spaces
        name = name.replace('_', ' ').replace('-', ' ').trim();
        if (name.isEmpty()) {
            return "Chapter";
        }
        // Title-case each word
        return Stream.of(name.split("\\s+"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    static List<String> extractIncludes(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = INCLUDE_LINE.matcher(line);
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static String readMaster(Path masterAbs, Path activeFile, String liveSource) throws IOException {
        // If the user is currently editing the master, prefer the live buffer.
        if (activeFile != null && activeFile.toAbsolutePath().normalize().equals(masterAbs)
                && liveSource != null) {
            return liveSource;
        }
        return readString(masterAbs);
    }

    private static String readString(Path file) throws IOException {
        return Files.readString(file);
    }

    private static String posix(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static boolean isAdocLike(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".adoc") || n.endsWith(".asciidoc") || n.endsWith(".asc")
                || n.endsWith(".ad") || n.endsWith(".asciidoctorconfig");
    }
}

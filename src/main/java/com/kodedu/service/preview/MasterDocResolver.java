package com.kodedu.service.preview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the top-level book(s) that include a given chapter file.
 *
 * <p>In multi-book projects (several top-level {@code .adoc} files sharing
 * one {@code .asciidoctorconfig}), opening a small chapter doesn't tell us
 * which book to render against — the same chapter may be included by more
 * than one master.  This resolver answers "which top-level adocs reach
 * {@code chapter} via {@code include::} (transitively)?"
 *
 * <p>No location-based shortcuts: a top-level {@code .adoc} can itself be
 * a child of a "book of books" master.  We always run the BFS over
 * include edges starting from every top-level file, and a file appears as
 * its own referrer when no other top-level book includes it (the BFS
 * trivially reaches its own start node).
 *
 * <p>Parsing is intentionally lightweight: a regex over the raw text
 * matching {@code include::PATH[OPTS]} directives.  We do not run
 * Asciidoctor itself (too slow), and we do not evaluate attribute
 * substitutions inside the include path beyond a couple of common forms
 * ({@code {docdir}}, {@code {includedir}}).  False positives are
 * preferred over false negatives — if we list an extra master in the
 * disambiguation dialog, the user picks the right one; if we omit one,
 * they have no way to recover.
 *
 * <p>Per-file include parsing is memoised in a static cache keyed by
 * {@code (path, last-modified-millis, size)}, so traversing several
 * book-of-books roots that share chapters re-reads each chapter at most
 * once until it changes on disk.
 */
public final class MasterDocResolver {

    private static final Logger logger = LoggerFactory.getLogger(MasterDocResolver.class);

    /** Match {@code include::path[opts]} (Asciidoctor include directive). */
    private static final Pattern INCLUDE = Pattern.compile(
            "(?m)^\\s*include::([^\\[\\n]+)\\[[^\\]]*\\]");

    /** Cap transitive walk depth so circular / huge graphs can't hang us. */
    private static final int MAX_DEPTH = 6;

    /** Cache entry: parsed include targets + the (mtime, size) snapshot
     *  that produced them. A change in either invalidates the entry. */
    private record CachedIncludes(long mtime, long size, List<Path> includes) {}

    /** Process-wide include cache. Bounded only by the number of distinct
     *  .adoc paths in a workspace, which is fine for typical projects. */
    private static final ConcurrentHashMap<Path, CachedIncludes> INCLUDE_CACHE =
            new ConcurrentHashMap<>();

    private MasterDocResolver() {}

    /**
     * Given a project root and an active chapter file, return the list of
     * top-level {@code .adoc} files in {@code projectDir} that transitively
     * include {@code chapter}.
     *
     * <p>The active file may itself be a top-level {@code .adoc}; if no
     * other book includes it, it appears in the result by virtue of the
     * BFS reaching its own start node.
     *
     * @return possibly empty list (never null), in directory-listing order
     */
    public static List<Path> findReferrers(Path projectDir, Path chapter) {
        if (projectDir == null || chapter == null) {
            return List.of();
        }
        Path chapterAbs = chapter.toAbsolutePath().normalize();
        List<Path> topLevel = listTopLevelAdocs(projectDir);
        if (topLevel.isEmpty()) {
            return List.of();
        }
        List<Path> hits = new ArrayList<>();
        for (Path top : topLevel) {
            if (reaches(top, chapterAbs)) {
                hits.add(top);
            }
        }
        return hits;
    }

    private static List<Path> listTopLevelAdocs(Path projectDir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectDir, "*.adoc")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not list .adoc in {}", projectDir, e);
        }
        return out;
    }

    /**
     * BFS from {@code start} following {@code include::} edges. Returns
     * true if any reachable file equals {@code targetAbs} (including the
     * start node itself). Bounded by {@link #MAX_DEPTH} and a visited-set
     * to handle cycles.
     */
    private static boolean reaches(Path start, Path targetAbs) {
        Path startAbs = start.toAbsolutePath().normalize();
        if (startAbs.equals(targetAbs)) {
            return true;
        }
        Set<Path> visited = new HashSet<>();
        Deque<Entry> queue = new ArrayDeque<>();
        queue.add(new Entry(startAbs, 0));
        visited.add(startAbs);
        while (!queue.isEmpty()) {
            Entry cur = queue.removeFirst();
            if (cur.depth >= MAX_DEPTH) {
                continue;
            }
            for (Path inc : parseIncludes(cur.file)) {
                Path incAbs = inc.toAbsolutePath().normalize();
                if (incAbs.equals(targetAbs)) {
                    return true;
                }
                if (visited.add(incAbs)) {
                    if (Files.isRegularFile(incAbs)) {
                        queue.add(new Entry(incAbs, cur.depth + 1));
                    }
                }
            }
        }
        return false;
    }

    private record Entry(Path file, int depth) {}

    /**
     * Read {@code file} and return the resolved Path of every {@code include::}
     * directive's target. Attribute substitutions other than {@code {docdir}} /
     * {@code {includedir}} are not evaluated; such includes simply won't
     * match (acceptable false negative).
     *
     * <p>Memoised by {@code (path, mtime, size)} — repeat calls with no
     * disk change return the cached list without re-reading or re-parsing.
     */
    private static List<Path> parseIncludes(Path file) {
        long mtime;
        long size;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (Exception e) {
            return List.of();
        }
        CachedIncludes cached = INCLUDE_CACHE.get(file);
        if (cached != null && cached.mtime == mtime && cached.size == size) {
            return cached.includes;
        }
        List<Path> out = new ArrayList<>();
        String text;
        try {
            text = Files.readString(file);
        } catch (Exception e) {
            return out;
        }
        Path baseDir = file.getParent();
        if (baseDir == null) {
            return out;
        }
        Matcher m = INCLUDE.matcher(text);
        while (m.find()) {
            String raw = m.group(1).trim();
            // Strip a couple of common attribute prefixes so relative
            // resolution works when authors do include::{docdir}/foo.adoc[]
            raw = raw.replace("{docdir}", baseDir.toString())
                     .replace("{includedir}", baseDir.toString());
            if (raw.contains("{") || raw.isEmpty()) {
                // Unresolved attribute reference — skip rather than guess.
                continue;
            }
            try {
                Path resolved = baseDir.resolve(raw).normalize();
                out.add(resolved);
            } catch (Exception ignored) {
                // Bad path — skip.
            }
        }
        INCLUDE_CACHE.put(file, new CachedIncludes(mtime, size, List.copyOf(out)));
        return out;
    }
}

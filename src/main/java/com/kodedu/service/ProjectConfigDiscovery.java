package com.kodedu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Scans the project working directory for Asciidoctor Ruby extensions
 * ({@code *.rb} files that register processors with {@code Asciidoctor::Extensions}).
 *
 * <p>This complements the conventional {@code .asciidoctor/lib} directory:
 * extensions found anywhere under the project (up to {@link #MAX_DEPTH})
 * are loaded automatically.
 *
 * <p>The walker is fault-tolerant: inaccessible directories (Windows junctions
 * such as {@code C:\Users\<user>\AppData\Local\Application Data}, files lacking
 * read permission, etc.) are silently skipped instead of aborting the entire
 * scan.  The walker also refuses to descend into the user's home directory
 * itself, which is the default working directory before a project is opened.
 *
 * <p>For project-level <em>attributes</em> (PDF theme, Mermaid config, etc.)
 * see {@link AsciidoctorConfigLoader}, which reads {@code .asciidoctorconfig}
 * files following the Asciidoctor convention.
 */
public class ProjectConfigDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(ProjectConfigDiscovery.class);

    /** Max directory depth to scan from the working directory root. */
    private static final int MAX_DEPTH = 4;

    /** Directory names skipped during the walk to keep scans fast and safe. */
    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "out",
            "dist", ".gradle", ".idea", ".vscode", ".cache", ".m2",
            "AppData", "Library", "Applications");

    /** Cache of resolveRubyExtensions results keyed on absolute workingDir + a fingerprint
     *  of all relevant inputs (.asciidoctorconfig mtimes), to avoid re-walking the project
     *  tree on every conversion. */
    private static final java.util.concurrent.ConcurrentMap<String, List<Path>> EXT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Resolve project Ruby extensions, preferring an explicit
     * {@code :asciidoctor-extensions:} declaration in
     * {@code .asciidoctorconfig} over the heuristic walker.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code .asciidoctorconfig} sets {@code asciidoctor-extensions}
     *       to a comma- or semicolon-separated list of paths (files, directories,
     *       or simple {@code *.rb} globs), resolve only those.  Relative paths
     *       are taken to be relative to {@code configdir} (already expanded by
     *       {@link AsciidoctorConfigLoader}); a final fallback resolves them
     *       against {@code workingDir}.  Directories contribute every
     *       {@code *.rb} / {@code *.jar} file directly inside (one level deep).</li>
     *   <li>Otherwise, fall back to {@link #discoverRubyExtensions(Path)} —
     *       the heuristic full-tree walker.</li>
     * </ol>
     *
     * <p>The conventional {@code .asciidoctor/lib} directory is independent
     * of this method and is loaded by {@code AsciidoctorFactory} regardless.
     */
    public static List<Path> resolveRubyExtensions(Path workingDir) {
        if (!isUsableProjectRoot(workingDir, "ruby extensions")) {
            return List.of();
        }
        String cacheKey = cacheKeyFor(workingDir);
        List<Path> cached = EXT_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> projectConfig = AsciidoctorConfigLoader.load(workingDir);
        Object explicit = projectConfig.get("asciidoctor-extensions");
        List<Path> resolved;
        if (explicit instanceof String s && !s.isBlank()) {
            resolved = resolveExplicitExtensionList(workingDir, s);
            logger.info("Loaded {} Ruby extension(s) from explicit "
                    + ":asciidoctor-extensions: in .asciidoctorconfig: {}",
                    resolved.size(), resolved);
        } else {
            resolved = discoverRubyExtensions(workingDir);
        }
        EXT_CACHE.put(cacheKey, resolved);
        return resolved;
    }

    /** Build a cache key that changes whenever .asciidoctorconfig content (mtime) changes. */
    private static String cacheKeyFor(Path workingDir) {
        StringBuilder sb = new StringBuilder(workingDir.toAbsolutePath().normalize().toString());
        Path home;
        try {
            home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            home = null;
        }
        Path dir = workingDir.toAbsolutePath().normalize();
        while (dir != null && (home == null || !dir.equals(home))) {
            for (String name : new String[]{".asciidoctorconfig", ".asciidoctorconfig.adoc"}) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    try {
                        sb.append('|').append(candidate)
                          .append('@').append(Files.getLastModifiedTime(candidate).toMillis());
                    } catch (IOException ignored) {
                        // missing/locked — fall back to path-only key
                        sb.append('|').append(candidate);
                    }
                }
            }
            dir = dir.getParent();
        }
        return sb.toString();
    }

    /** Test hook — clear the resolution cache. */
    static void resetCacheForTest() {
        EXT_CACHE.clear();
    }

    private static List<Path> resolveExplicitExtensionList(Path workingDir, String spec) {
        Set<Path> out = new LinkedHashSet<>();
        for (String token : spec.split("[,;]")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            List<Path> candidates = expandToken(workingDir, trimmed);
            for (Path candidate : candidates) {
                if (Files.isDirectory(candidate)) {
                    addRubyAndJarFilesShallow(candidate, out);
                } else if (Files.isRegularFile(candidate)) {
                    out.add(candidate.toAbsolutePath().normalize());
                } else {
                    logger.warn("asciidoctor-extensions entry not found: '{}' "
                            + "(resolved to {})", trimmed, candidate);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Expand a single token (absolute path, relative path, or simple glob) to candidate Paths. */
    private static List<Path> expandToken(Path workingDir, String token) {
        // Strip surrounding quotes a user might have added.
        if ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'"))) {
            token = token.substring(1, token.length() - 1);
        }
        // Normalize separators so both "ext/*.rb" and "ext\\*.rb" work cross-platform.
        String normalized = token.replace('\\', '/');

        // Trailing-glob support: split on the last '/' and pass parent + pattern
        // separately, because Paths.get rejects '*' / '?' on Windows.
        int lastSlash = normalized.lastIndexOf('/');
        String parentPart = lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
        String namePart = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

        if (namePart.contains("*") || namePart.contains("?")) {
            Path parent;
            try {
                parent = parentPart.isEmpty() ? workingDir : Paths.get(parentPart);
            } catch (Exception e) {
                logger.warn("Skipping malformed asciidoctor-extensions glob '{}': {}", token, e.toString());
                return List.of();
            }
            if (!parent.isAbsolute()) {
                parent = workingDir.resolve(parent);
            }
            if (!Files.isDirectory(parent)) {
                logger.warn("asciidoctor-extensions glob parent not found: '{}' (resolved to {})",
                        token, parent);
                return List.of();
            }
            try (var stream = Files.newDirectoryStream(parent, namePart)) {
                List<Path> matches = new ArrayList<>();
                for (Path p : stream) matches.add(p);
                return matches;
            } catch (IOException e) {
                logger.warn("Failed to expand glob '{}': {}", token, e.toString());
                return List.of();
            }
        }

        Path base;
        try {
            base = Paths.get(normalized);
        } catch (Exception e) {
            logger.warn("Skipping malformed asciidoctor-extensions entry '{}': {}", token, e.toString());
            return List.of();
        }
        if (!base.isAbsolute()) {
            base = workingDir.resolve(base);
        }
        return List.of(base);
    }

    private static void addRubyAndJarFilesShallow(Path dir, Set<Path> out) {
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".rb") || n.endsWith(".jar");
                    })
                    .sorted()
                    .forEach(p -> out.add(p.toAbsolutePath().normalize()));
        } catch (IOException e) {
            logger.warn("Could not list extension dir {}: {}", dir, e.toString());
        }
    }

    /**
     * 
    /**
     * Finds {@code .rb} files anywhere under {@code workingDir} (up to
     * {@link #MAX_DEPTH}) whose content registers an Asciidoctor extension.
     * The conventional {@code .asciidoctor/lib} results are <em>not</em>
     * included here — the caller should merge both lists.
     */
    public static List<Path> discoverRubyExtensions(Path workingDir) {
        if (!isUsableProjectRoot(workingDir, "ruby extensions")) {
            return List.of();
        }
        List<Path> matches = walkSafely(workingDir,
                p -> p.toString().endsWith(".rb")
                        && !isInsideDotAsciidoctor(workingDir, p)
                        && isAsciidoctorExtension(p));
        Collections.sort(matches);
        if (!matches.isEmpty()) {
            logger.info("Discovered {} project Ruby extension(s) under {}: {}",
                    matches.size(), workingDir, matches);
        } else {
            logger.debug("No project Ruby extensions discovered under {}", workingDir);
        }
        return matches;
    }

    private static boolean isInsideDotAsciidoctor(Path root, Path candidate) {
        Path relative = root.relativize(candidate);
        return relative.toString().replace('\\', '/').startsWith(".asciidoctor/");
    }

    /** Lightweight check: read the first 8 KB and look for extension registration. */
    private static boolean isAsciidoctorExtension(Path file) {
        try {
            String head = readHead(file, 8192);
            return head.contains("Asciidoctor::Extensions") ||
                   head.contains("asciidoctor/extensions");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if {@code dir} looks like a real project root we
     * should scan.  Refuses null, non-directory, and the user's home dir.
     */
    private static boolean isUsableProjectRoot(Path dir, String purpose) {
        if (Objects.isNull(dir) || !Files.isDirectory(dir)) {
            logger.debug("Skipping {} discovery: not a directory: {}", purpose, dir);
            return false;
        }
        try {
            Path normalized = dir.toAbsolutePath().normalize();
            Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
            if (normalized.equals(home)) {
                logger.info("Skipping {} discovery: working dir is user home ({}); "
                        + "open a project folder or file to enable auto-discovery.", purpose, home);
                return false;
            }
        } catch (Exception e) {
            // fall through and try the scan
        }
        return true;
    }

    /**
     * Walks {@code root} up to {@link #MAX_DEPTH} and collects files matching
     * {@code matcher}.  Inaccessible directories and files (Windows junctions,
     * permission errors) are silently skipped instead of aborting the walk.
     */
    private static List<Path> walkSafely(Path root, Predicate<Path> matcher) {
        List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                            if (!dir.equals(root) && SKIP_DIR_NAMES.contains(name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(root) && name.startsWith(".")
                                    && !name.equals(".asciidoctor") && !name.equals(".github")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            try {
                                if (matcher.test(file)) {
                                    matches.add(file);
                                }
                            } catch (Exception e) {
                                logger.debug("Ignoring file during scan {}: {}", file, e.toString());
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // Junctions like "C:\Users\<u>\AppData\Local\Application Data"
                            // throw AccessDeniedException — skip the offender, keep going.
                            logger.debug("Skipping unreadable path during scan {}: {}", file, exc.toString());
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            logger.warn("Project scan aborted at root {}: {}", root, e.toString());
        }
        return matches;
    }

    private static String readHead(Path file, int maxBytes) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int len = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }
}

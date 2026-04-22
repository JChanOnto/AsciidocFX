package com.kodedu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust resolver for external CLI tools used by Asciidoctor extensions
 * (mmdc, vg2png, vg2svg, nomnoml, bytefield, …).
 *
 * <p>Resolution order — first hit wins:
 * <ol>
 *   <li>Explicit attribute value (absolute path or relative to the doc dir),
 *       with Windows extension fallbacks ({@code .cmd}, {@code .exe},
 *       {@code .bat}, {@code .ps1}).</li>
 *   <li>{@code <docdir>/node_modules/.bin/<name>} walking up parent
 *       directories until the filesystem root or user home is reached.
 *       Handles npm/yarn workspaces where {@code node_modules} sits at the
 *       repo root rather than next to the doc.</li>
 *   <li>The OS {@code PATH} environment variable, with the platform-specific
 *       executable extensions appended on Windows.</li>
 *   <li>Per-tool well-known install locations (npm global prefix, scoop
 *       shims, common UNIX bin dirs).</li>
 * </ol>
 *
 * <p>If nothing resolves the resolver returns {@code null} (and logs a
 * single warning per tool name to avoid spam).  Callers should treat that
 * as "tool not available" and surface a friendly error to the user instead
 * of crashing the conversion pipeline.
 */
public final class ExecutableResolver {

    private static final Logger logger = LoggerFactory.getLogger(ExecutableResolver.class);

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Extensions to try on Windows when the bare name isn't found.
     *  Order matters: prefer real Windows executables over the bare name,
     *  which is often a POSIX shell script (e.g. npm-installed {@code mmdc})
     *  that {@code Runtime.exec} cannot run on Windows. */
    private static final List<String> WINDOWS_EXEC_EXTS =
            List.of(".cmd", ".exe", ".bat", ".ps1", "");

    /** Extensions to try on POSIX (just the bare name). */
    private static final List<String> POSIX_EXEC_EXTS = List.of("");

    /** Max parents to walk up when looking for {@code node_modules/.bin}. */
    private static final int MAX_WALK_UP = 8;

    /** Per-tool fallback install locations.  Resolved against env vars at lookup time. */
    private static final Map<String, List<String>> WELL_KNOWN_LOCATIONS = Map.of(
            "mmdc", List.of(
                    "${APPDATA}/npm/mmdc",
                    "${USERPROFILE}/AppData/Roaming/npm/mmdc",
                    "${USERPROFILE}/scoop/shims/mmdc",
                    "${USERPROFILE}/.npm-global/bin/mmdc",
                    "${HOME}/.npm-global/bin/mmdc",
                    "/usr/local/bin/mmdc",
                    "/opt/homebrew/bin/mmdc"),
            "vg2png", List.of(
                    "${APPDATA}/npm/vg2png",
                    "${USERPROFILE}/AppData/Roaming/npm/vg2png",
                    "/usr/local/bin/vg2png"),
            "vg2svg", List.of(
                    "${APPDATA}/npm/vg2svg",
                    "${USERPROFILE}/AppData/Roaming/npm/vg2svg",
                    "/usr/local/bin/vg2svg"),
            "nomnoml", List.of(
                    "${APPDATA}/npm/nomnoml",
                    "${USERPROFILE}/AppData/Roaming/npm/nomnoml",
                    "/usr/local/bin/nomnoml"),
            "bytefield", List.of(
                    "${APPDATA}/npm/bytefield-svg",
                    "${USERPROFILE}/AppData/Roaming/npm/bytefield-svg",
                    "/usr/local/bin/bytefield-svg"));

    /** Tools we've already warned about (avoid log spam). */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Cache of full resolution results (incl. negative results stored as NULL_PATH). */
    private static final java.util.concurrent.ConcurrentMap<String, Path> RESOLVE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Sentinel meaning "already searched, not found" — ConcurrentHashMap forbids null values. */
    private static final Path NULL_PATH = Paths.get("__executable_resolver_negative_cache__");

    private ExecutableResolver() {}

    /**
     * Resolve {@code toolName} to an executable Path.
     *
     * @param toolName       short name, e.g. {@code "mmdc"}
     * @param explicitValue  the value of the matching document attribute
     *                       (e.g. set via {@code :mmdc: ./node_modules/.bin/mmdc}),
     *                       or {@code null} if unset
     * @param docDir         the document working directory; may be {@code null}
     * @return the resolved absolute Path, or {@code null} if not found
     */
    public static Path resolve(String toolName, String explicitValue, Path docDir) {
        String cacheKey = toolName + "\0" + (explicitValue == null ? "" : explicitValue)
                + "\0" + (docDir == null ? "" : docDir.toAbsolutePath().normalize().toString());
        Path cached = RESOLVE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached == NULL_PATH ? null : cached;
        }
        Path resolved = doResolve(toolName, explicitValue, docDir);
        RESOLVE_CACHE.put(cacheKey, resolved == null ? NULL_PATH : resolved);
        return resolved;
    }

    private static Path doResolve(String toolName, String explicitValue, Path docDir) {
        // 1. Explicit value
        if (explicitValue != null && !explicitValue.isBlank()) {
            Path hit = tryWithExts(Paths.get(explicitValue));
            if (hit != null) return logHit(toolName, hit, "explicit attribute");
            if (docDir != null) {
                Path resolved = docDir.resolve(explicitValue);
                hit = tryWithExts(resolved);
                if (hit != null) return logHit(toolName, hit, "explicit attribute (docdir-relative)");
            }
            // Fall through — explicit value didn't resolve, try the chain anyway
            // rather than failing outright.  Common case: .asciidoctorconfig
            // sets :mmdc: ./node_modules/.bin/mmdc but node_modules isn't installed.
        }

        // 2. node_modules/.bin walking up from docDir
        if (docDir != null) {
            Path nodeHit = findInNodeModules(toolName, docDir);
            if (nodeHit != null) return logHit(toolName, nodeHit, "node_modules/.bin");
        }

        // 3. OS PATH
        Path pathHit = findOnPath(toolName);
        if (pathHit != null) return logHit(toolName, pathHit, "PATH");

        // 4. Well-known install locations
        Path wellKnown = findInWellKnownLocations(toolName);
        if (wellKnown != null) return logHit(toolName, wellKnown, "well-known install location");

        if (WARNED.add(toolName)) {
            logger.warn("Could not locate '{}' executable. Searched: explicit attribute, "
                    + "node_modules/.bin (walking up from docdir), PATH, and well-known install "
                    + "locations. Install it (e.g. `npm install -g @mermaid-js/mermaid-cli` for mmdc) "
                    + "or set the :{}: document attribute to its absolute path.", toolName, toolName);
        }
        return null;
    }

    private static Path findInNodeModules(String toolName, Path docDir) {
        Path dir = docDir.toAbsolutePath().normalize();
        Path home = userHome();
        for (int i = 0; i < MAX_WALK_UP && dir != null; i++) {
            Path bin = dir.resolve("node_modules").resolve(".bin").resolve(toolName);
            Path hit = tryWithExts(bin);
            if (hit != null) return hit;
            if (home != null && dir.equals(home)) break;
            dir = dir.getParent();
        }
        return null;
    }

    private static Path findOnPath(String toolName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) return null;
        String sep = System.getProperty("path.separator", IS_WINDOWS ? ";" : ":");
        for (String entry : pathEnv.split(java.util.regex.Pattern.quote(sep))) {
            if (entry.isBlank()) continue;
            try {
                Path candidate = Paths.get(entry).resolve(toolName);
                Path hit = tryWithExts(candidate);
                if (hit != null) return hit;
            } catch (Exception ignored) {
                // malformed PATH entry, skip
            }
        }
        return null;
    }

    private static Path findInWellKnownLocations(String toolName) {
        List<String> patterns = WELL_KNOWN_LOCATIONS.get(toolName);
        if (patterns == null) return null;
        Set<Path> tried = new LinkedHashSet<>();
        for (String pattern : patterns) {
            String expanded = expandEnvVars(pattern);
            if (expanded == null) continue;
            try {
                Path candidate = Paths.get(expanded);
                if (!tried.add(candidate)) continue;
                Path hit = tryWithExts(candidate);
                if (hit != null) return hit;
            } catch (Exception ignored) {
                // skip malformed candidate
            }
        }
        return null;
    }

    /** Replace {@code ${NAME}} tokens with environment variables.  Returns null if any required var is missing. */
    private static String expandEnvVars(String pattern) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            int start = pattern.indexOf("${", i);
            if (start < 0) {
                out.append(pattern, i, pattern.length());
                break;
            }
            out.append(pattern, i, start);
            int end = pattern.indexOf('}', start + 2);
            if (end < 0) return null;
            String name = pattern.substring(start + 2, end);
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                if ("HOME".equals(name)) {
                    value = System.getProperty("user.home");
                }
                if (value == null || value.isBlank()) return null;
            }
            out.append(value);
            i = end + 1;
        }
        return out.toString();
    }

    /** Try {@code base} with each platform extension; return the first existing executable file. */
    private static Path tryWithExts(Path base) {
        if (base == null) return null;
        List<String> exts = IS_WINDOWS ? WINDOWS_EXEC_EXTS : POSIX_EXEC_EXTS;
        for (String ext : exts) {
            Path candidate = ext.isEmpty() ? base : base.resolveSibling(base.getFileName() + ext);
            if (Files.isRegularFile(candidate) && (IS_WINDOWS || Files.isExecutable(candidate))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path userHome() {
        try {
            return Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static Path logHit(String toolName, Path hit, String source) {
        // Only log first resolution per tool to avoid noise.
        if (FIRST_HIT_LOGGED.add(toolName)) {
            logger.info("Resolved '{}' via {}: {}", toolName, source, hit);
        } else {
            logger.debug("Resolved '{}' via {}: {}", toolName, source, hit);
        }
        WARNED.remove(toolName);
        return hit;
    }

    private static final Set<String> FIRST_HIT_LOGGED = ConcurrentHashMap.newKeySet();

    /** Test hook — clear cached "already warned/logged" state and resolution cache. */
    static void resetForTest() {
        WARNED.clear();
        FIRST_HIT_LOGGED.clear();
        RESOLVE_CACHE.clear();
    }

    /** Convenience for callers that just want to know if a tool is available. */
    public static boolean isAvailable(String toolName, String explicitValue, Path docDir) {
        return Objects.nonNull(resolve(toolName, explicitValue, docDir));
    }

    /** Returns the list of extensions known to this resolver (for tests / debugging). */
    public static List<String> knownExtensionExts() {
        return new ArrayList<>(IS_WINDOWS ? WINDOWS_EXEC_EXTS : POSIX_EXEC_EXTS);
    }
}

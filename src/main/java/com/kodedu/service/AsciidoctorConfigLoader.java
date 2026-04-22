package com.kodedu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads project-level Asciidoctor attributes from {@code .asciidoctorconfig}
 * files (the Asciidoctor convention also honored by the IntelliJ AsciiDoc
 * plugin and Atom/VSCode plugins).
 *
 * <p>Discovery walks upward from the project working directory toward the
 * filesystem root, stopping at the user's home directory.  Outer files are
 * loaded first; attributes in inner (closer-to-doc) files override.
 *
 * <p>Supported syntax (one entry per line):
 * <pre>
 *   :name: value          // set attribute
 *   :name:                // set attribute to empty (treated as enabled)
 *   :!name:               // unset attribute
 *   :name!:               // unset attribute
 *   // line comment       (ignored)
 * </pre>
 *
 * <p>The token {@code {asciidoctorconfigdir}} (the Asciidoctor convention,
 * also honored by the IntelliJ AsciiDoc plugin and the {@code asciidoctor}
 * CLI) in a value is replaced with the absolute directory containing the
 * config file.  Other attribute references (e.g. {@code {docdir}}) are
 * passed through verbatim and resolved by Asciidoctor during conversion.
 */
public final class AsciidoctorConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(AsciidoctorConfigLoader.class);

    private static final String[] CONFIG_FILE_NAMES = {
            ".asciidoctorconfig", ".asciidoctorconfig.adoc"
    };

    /** Matches {@code :name: value}, {@code :name:}, {@code :!name:}, {@code :name!:}. */
    private static final Pattern ATTR_LINE = Pattern.compile(
            "^:(?<unsetPre>!)?(?<name>[A-Za-z0-9_][A-Za-z0-9_-]*)(?<unsetPost>!)?:(?:\\s+(?<value>.*))?\\s*$");

    /** Per-file parse cache keyed on absolute path; invalidated by mtime change. */
    private static final Map<Path, ParsedConfig> PARSE_CACHE = new ConcurrentHashMap<>();

    private record ParsedConfig(FileTime mtime, Map<String, Object> entries) {}

    private AsciidoctorConfigLoader() {}

    /**
     * Loads merged attributes from any {@code .asciidoctorconfig} files found
     * by walking up from {@code startDir} toward the filesystem root.
     *
     * @param startDir the directory to start searching from (typically the
     *                 project working directory)
     * @return ordered map of attribute name to value; empty if no config
     *         files found.  Attributes that were explicitly unset (via
     *         {@code :!name:}) are removed from the result rather than
     *         exposed as null.
     */
    public static Map<String, Object> load(Path startDir) {
        if (Objects.isNull(startDir) || !Files.isDirectory(startDir)) {
            return Map.of();
        }
        List<Path> configFiles = findConfigFiles(startDir);
        if (configFiles.isEmpty()) {
            logger.debug("No .asciidoctorconfig found from {}", startDir);
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Path cfg : configFiles) {
            Map<String, Object> entries = parse(cfg);
            int set = 0, unset = 0;
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                if (e.getValue() == null) {
                    merged.remove(e.getKey());
                    unset++;
                } else {
                    merged.put(e.getKey(), e.getValue());
                    set++;
                }
            }
            logger.debug("Loaded {} ({} set, {} unset)", cfg, set, unset);
        }
        return merged;
    }

    /** Outer-most (highest in tree) first, innermost last. */
    private static List<Path> findConfigFiles(Path startDir) {
        Path home;
        try {
            home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            home = null;
        }
        Deque<Path> chain = new ArrayDeque<>();
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            if (home != null && dir.equals(home)) {
                break;
            }
            for (String name : CONFIG_FILE_NAMES) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    chain.addFirst(candidate); // outer-first
                }
            }
            dir = dir.getParent();
        }
        return new ArrayList<>(chain);
    }

    private static Map<String, Object> parse(Path cfg) {
        Path key = cfg.toAbsolutePath().normalize();
        FileTime currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(key);
        } catch (IOException e) {
            currentMtime = null;
        }
        ParsedConfig cached = PARSE_CACHE.get(key);
        if (cached != null && currentMtime != null && currentMtime.equals(cached.mtime())) {
            return cached.entries();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(cfg);
        } catch (IOException e) {
            logger.warn("Could not read {}: {}", cfg, e.toString());
            return out;
        }
        String configDir = cfg.getParent().toAbsolutePath().toString();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            Matcher m = ATTR_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group("name");
            boolean unset = m.group("unsetPre") != null || m.group("unsetPost") != null;
            if (unset) {
                out.put(name, null); // sentinel meaning "unset"
                continue;
            }
            String value = m.group("value");
            if (value == null) {
                out.put(name, "");
            } else {
                String expanded = value.replace("{asciidoctorconfigdir}", configDir);
                out.put(name, expanded);
            }
        }
        if (currentMtime != null) {
            PARSE_CACHE.put(key, new ParsedConfig(currentMtime, out));
        }
        return out;
    }

    /** Test hook — clears the parse cache. */
    static void resetCacheForTest() {
        PARSE_CACHE.clear();
    }
}

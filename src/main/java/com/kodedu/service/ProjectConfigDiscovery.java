package com.kodedu.service;

import com.kodedu.helper.IOHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers project-level Asciidoctor resources (Ruby extensions, PDF themes,
 * Mermaid configuration) by scanning the working directory.  No paths or folder
 * names are hard-coded — discovery is based on file extensions and lightweight
 * content validation.
 */
public class ProjectConfigDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(ProjectConfigDiscovery.class);

    /** Max directory depth to scan from the working directory root. */
    private static final int MAX_DEPTH = 4;

    // -- Ruby extension discovery ------------------------------------------------

    /**
     * Finds {@code .rb} files anywhere under {@code workingDir} (up to
     * {@link #MAX_DEPTH}) whose content registers an Asciidoctor extension.
     * The existing {@code .asciidoctor/lib} results are <em>not</em> included
     * here — the caller should merge both lists.
     */
    public static List<Path> discoverRubyExtensions(Path workingDir) {
        if (Objects.isNull(workingDir) || !Files.isDirectory(workingDir)) {
            return List.of();
        }
        return IOHelper.walk(workingDir, MAX_DEPTH)
                .filter(p -> p.toString().endsWith(".rb"))
                .filter(p -> !isInsideDotAsciidoctor(workingDir, p))
                .filter(ProjectConfigDiscovery::isAsciidoctorExtension)
                .sorted()
                .collect(Collectors.toList());
    }

    private static boolean isInsideDotAsciidoctor(Path root, Path candidate) {
        Path relative = root.relativize(candidate);
        return relative.toString().replace('\\', '/').startsWith(".asciidoctor/");
    }

    /**
     * Lightweight check: read the first 8 KB and look for any of the
     * canonical Asciidoctor extension registration patterns.
     */
    private static boolean isAsciidoctorExtension(Path file) {
        try {
            String head = readHead(file, 8192);
            return head.contains("Asciidoctor::Extensions") ||
                   head.contains("asciidoctor/extensions");
        } catch (Exception e) {
            return false;
        }
    }

    // -- PDF theme discovery -----------------------------------------------------

    /**
     * Result holder for a discovered asciidoctor-pdf theme.
     *
     * @param themeName   the name portion before {@code -theme.yml}
     * @param themesDir   the parent directory of the theme file
     */
    public record ThemeInfo(String themeName, Path themesDir) {}

    /**
     * Scans {@code workingDir} for files matching {@code *-theme.yml} whose
     * content looks like a valid asciidoctor-pdf theme (contains expected
     * top-level keys such as {@code page:}, {@code base:}, or
     * {@code heading:}).
     *
     * @return an {@link Optional} containing the first matching theme, or
     *         empty if none found.
     */
    public static Optional<ThemeInfo> discoverPdfTheme(Path workingDir) {
        if (Objects.isNull(workingDir) || !Files.isDirectory(workingDir)) {
            return Optional.empty();
        }
        return IOHelper.walk(workingDir, MAX_DEPTH)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith("-theme.yml") || name.endsWith("-theme.yaml");
                })
                .filter(ProjectConfigDiscovery::isAsciidoctorPdfTheme)
                .findFirst()
                .map(p -> {
                    String fileName = p.getFileName().toString();
                    // "truedac-theme.yml" → "truedac"
                    String themeName = fileName.replaceFirst("-theme\\.ya?ml$", "");
                    return new ThemeInfo(themeName, p.getParent());
                });
    }

    /**
     * Validates that a YAML file contains at least one asciidoctor-pdf
     * theme key so we don't pick up random YAML files.
     */
    private static boolean isAsciidoctorPdfTheme(Path file) {
        try {
            String head = readHead(file, 4096);
            // Look for top-level keys specific to asciidoctor-pdf themes
            return Stream.of("page:", "base:", "heading:", "title_page:", "header:", "footer:", "extends:")
                    .anyMatch(key -> head.contains(key));
        } catch (Exception e) {
            return false;
        }
    }

    // -- Mermaid config discovery -------------------------------------------------

    /**
     * Scans for JSON files whose name contains "mermaid" and "config" and
     * whose content looks like a Mermaid configuration (has a
     * {@code theme} or {@code flowchart} key).
     *
     * @return the path to the first matching file, or empty.
     */
    public static Optional<Path> discoverMermaidConfig(Path workingDir) {
        if (Objects.isNull(workingDir) || !Files.isDirectory(workingDir)) {
            return Optional.empty();
        }
        return IOHelper.walk(workingDir, MAX_DEPTH)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".json") &&
                           name.contains("mermaid") &&
                           name.contains("config");
                })
                .filter(ProjectConfigDiscovery::isMermaidConfig)
                .findFirst();
    }

    private static boolean isMermaidConfig(Path file) {
        try {
            String head = readHead(file, 2048);
            return (head.contains("\"theme\"") || head.contains("\"flowchart\"") ||
                    head.contains("\"themeVariables\""));
        } catch (Exception e) {
            return false;
        }
    }

    // -- helpers ------------------------------------------------------------------

    private static String readHead(Path file, int maxBytes) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int len = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }
}

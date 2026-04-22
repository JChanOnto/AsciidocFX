package com.kodedu.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectConfigDiscovery}.
 */
class ProjectConfigDiscoveryTest {

    @BeforeEach
    void resetCaches() {
        ProjectConfigDiscovery.resetCacheForTest();
        AsciidoctorConfigLoader.resetCacheForTest();
    }

    private static final String EXTENSION_REGISTRATION =
            "Asciidoctor::Extensions.register do\n  block_macro :hello\nend\n";

    // ---- discoverRubyExtensions (heuristic walker) ----

    @Test
    void discoverReturnsEmptyWhenWorkingDirIsNull() {
        assertTrue(ProjectConfigDiscovery.discoverRubyExtensions(null).isEmpty());
    }

    @Test
    void discoverReturnsEmptyWhenWorkingDirIsNotADirectory(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("a-file"));
        assertTrue(ProjectConfigDiscovery.discoverRubyExtensions(file).isEmpty());
    }

    @Test
    void discoverFindsRubyExtensionsContainingRegistration(@TempDir Path tmp) throws IOException {
        Path libDir = Files.createDirectories(tmp.resolve("lib"));
        Path ext = libDir.resolve("hello.rb");
        Files.writeString(ext, EXTENSION_REGISTRATION);
        // A .rb file that is NOT an extension should be ignored.
        Files.writeString(libDir.resolve("plain.rb"), "puts 'not an extension'\n");

        List<Path> result = ProjectConfigDiscovery.discoverRubyExtensions(tmp);

        assertEquals(1, result.size());
        assertEquals(ext.toAbsolutePath().normalize(), result.get(0).toAbsolutePath().normalize());
    }

    @Test
    void discoverSkipsConventionalDotAsciidoctorLibTree(@TempDir Path tmp) throws IOException {
        Path conventional = Files.createDirectories(tmp.resolve(".asciidoctor").resolve("lib"));
        Files.writeString(conventional.resolve("conv.rb"), EXTENSION_REGISTRATION);
        Path other = Files.createDirectories(tmp.resolve("ext"));
        Path keep = other.resolve("keep.rb");
        Files.writeString(keep, EXTENSION_REGISTRATION);

        List<Path> result = ProjectConfigDiscovery.discoverRubyExtensions(tmp);

        assertEquals(1, result.size(),
                ".asciidoctor/lib/* must be excluded; loaded separately by AsciidoctorFactory.");
        assertEquals(keep.toAbsolutePath().normalize(),
                result.get(0).toAbsolutePath().normalize());
    }

    @Test
    void discoverSkipsKnownNoiseDirectories(@TempDir Path tmp) throws IOException {
        for (String skipped : List.of("node_modules", "target", "build", ".git")) {
            Path dir = Files.createDirectories(tmp.resolve(skipped).resolve("inner"));
            Files.writeString(dir.resolve("ext.rb"), EXTENSION_REGISTRATION);
        }
        // Plus one valid one
        Path good = tmp.resolve("plugins").resolve("good.rb");
        Files.createDirectories(good.getParent());
        Files.writeString(good, EXTENSION_REGISTRATION);

        List<Path> result = ProjectConfigDiscovery.discoverRubyExtensions(tmp);

        assertEquals(1, result.size());
        assertEquals(good.toAbsolutePath().normalize(),
                result.get(0).toAbsolutePath().normalize());
    }

    @Test
    void discoverRefusesUserHome(@TempDir Path tmp) {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toAbsolutePath().toString());
            List<Path> result = ProjectConfigDiscovery.discoverRubyExtensions(tmp);
            assertTrue(result.isEmpty(),
                    "Walker must refuse to scan when working dir equals user home.");
        } finally {
            if (previous != null) System.setProperty("user.home", previous);
        }
    }

    // ---- resolveRubyExtensions (config-aware) ----

    @Test
    void resolveExplicitDirectoryEnumeratesShallowRubyAndJarFiles(@TempDir Path tmp) throws IOException {
        Path extDir = Files.createDirectories(tmp.resolve("theme").resolve("extensions"));
        Path rb = Files.writeString(extDir.resolve("a.rb"), "# a");
        Path jar = Files.write(extDir.resolve("b.jar"), new byte[]{1, 2, 3});
        Files.writeString(extDir.resolve("c.txt"), "ignored");
        // Nested file should NOT be included (shallow only).
        Path nested = Files.createDirectories(extDir.resolve("nested"));
        Files.writeString(nested.resolve("deep.rb"), "# deep");

        Files.writeString(tmp.resolve(".asciidoctorconfig"),
                ":asciidoctor-extensions: theme/extensions\n");

        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertEquals(2, result.size(), "Expected only the shallow .rb and .jar files");
        assertTrue(result.contains(rb.toAbsolutePath().normalize()));
        assertTrue(result.contains(jar.toAbsolutePath().normalize()));
    }

    @Test
    void resolveExplicitListSeparatedByCommasAndSemicolons(@TempDir Path tmp) throws IOException {
        Path a = Files.writeString(tmp.resolve("a.rb"), "# a");
        Path b = Files.writeString(tmp.resolve("b.rb"), "# b");
        Path c = Files.writeString(tmp.resolve("c.rb"), "# c");

        Files.writeString(tmp.resolve(".asciidoctorconfig"),
                ":asciidoctor-extensions: a.rb, b.rb ; c.rb\n");

        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertEquals(3, result.size());
        assertTrue(result.contains(a.toAbsolutePath().normalize()));
        assertTrue(result.contains(b.toAbsolutePath().normalize()));
        assertTrue(result.contains(c.toAbsolutePath().normalize()));
    }

    @Test
    void resolveExplicitGlobExpandsToMatchingFiles(@TempDir Path tmp) throws IOException {
        Path extDir = Files.createDirectories(tmp.resolve("ext"));
        Path one = Files.writeString(extDir.resolve("one.rb"), "# 1");
        Path two = Files.writeString(extDir.resolve("two.rb"), "# 2");
        Files.writeString(extDir.resolve("notes.txt"), "ignored");

        Files.writeString(tmp.resolve(".asciidoctorconfig"),
                ":asciidoctor-extensions: ext/*.rb\n");

        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertEquals(2, result.size());
        assertTrue(result.contains(one.toAbsolutePath().normalize()));
        assertTrue(result.contains(two.toAbsolutePath().normalize()));
    }

    @Test
    void resolveExplicitMissingEntryDoesNotCrash(@TempDir Path tmp) throws IOException {
        Path good = Files.writeString(tmp.resolve("good.rb"), "# ok");
        Files.writeString(tmp.resolve(".asciidoctorconfig"),
                ":asciidoctor-extensions: good.rb, missing.rb\n");

        // Should log a warning for missing.rb but still return good.rb.
        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertEquals(1, result.size());
        assertEquals(good.toAbsolutePath().normalize(), result.get(0));
    }

    @Test
    void resolveStripsQuotesFromExplicitEntries(@TempDir Path tmp) throws IOException {
        Path good = Files.writeString(tmp.resolve("ext.rb"), "# ok");
        Files.writeString(tmp.resolve(".asciidoctorconfig"),
                ":asciidoctor-extensions: \"ext.rb\"\n");

        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertEquals(1, result.size());
        assertEquals(good.toAbsolutePath().normalize(), result.get(0));
    }

    @Test
    void resolveFallsBackToWalkerWhenAttributeAbsent(@TempDir Path tmp) throws IOException {
        Path ext = tmp.resolve("plugins").resolve("ext.rb");
        Files.createDirectories(ext.getParent());
        Files.writeString(ext, EXTENSION_REGISTRATION);
        // No .asciidoctorconfig at all.

        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertEquals(1, result.size());
        assertEquals(ext.toAbsolutePath().normalize(),
                result.get(0).toAbsolutePath().normalize());
    }

    @Test
    void resolveRefusesUserHome(@TempDir Path tmp) {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toAbsolutePath().toString());
            assertTrue(ProjectConfigDiscovery.resolveRubyExtensions(tmp).isEmpty());
        } finally {
            if (previous != null) System.setProperty("user.home", previous);
        }
    }

    @Test
    void resolveReturnsEmptyForNullWorkingDir() {
        assertTrue(ProjectConfigDiscovery.resolveRubyExtensions(null).isEmpty());
    }

    @Test
    void emptyAsciidoctorExtensionsAttributeFallsBackToWalker(@TempDir Path tmp) throws IOException {
        Path ext = tmp.resolve("plugins").resolve("ext.rb");
        Files.createDirectories(ext.getParent());
        Files.writeString(ext, EXTENSION_REGISTRATION);
        // Attribute is present but blank — should not trigger explicit resolution.
        Files.writeString(tmp.resolve(".asciidoctorconfig"),
                ":asciidoctor-extensions:\n");

        List<Path> result = ProjectConfigDiscovery.resolveRubyExtensions(tmp);

        assertFalse(result.isEmpty(), "Blank :asciidoctor-extensions: should fall back to walker.");
        assertEquals(ext.toAbsolutePath().normalize(),
                result.get(0).toAbsolutePath().normalize());
    }
}

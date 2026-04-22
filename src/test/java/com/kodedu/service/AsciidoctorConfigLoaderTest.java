package com.kodedu.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AsciidoctorConfigLoader}.
 */
class AsciidoctorConfigLoaderTest {

    @BeforeEach
    void resetCache() {
        AsciidoctorConfigLoader.resetCacheForTest();
    }

    @Test
    void returnsEmptyMapWhenStartDirIsNull() {
        assertTrue(AsciidoctorConfigLoader.load(null).isEmpty());
    }

    @Test
    void returnsEmptyMapWhenStartDirIsNotADirectory(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("not-a-dir"));
        assertTrue(AsciidoctorConfigLoader.load(file).isEmpty());
    }

    @Test
    void returnsEmptyMapWhenNoConfigFilesFound(@TempDir Path tmp) {
        // tmp is below the user.home tree on most systems, but contains no config files.
        assertTrue(AsciidoctorConfigLoader.load(tmp).isEmpty());
    }

    @Test
    void parsesSimpleAttributes(@TempDir Path tmp) throws IOException {
        write(tmp.resolve(".asciidoctorconfig"),
                ":foo: bar",
                ":empty:",
                "// comment line",
                "",
                ":another-attr_1: value with spaces");

        Map<String, Object> result = AsciidoctorConfigLoader.load(tmp);

        assertEquals("bar", result.get("foo"));
        assertEquals("", result.get("empty"));
        assertEquals("value with spaces", result.get("another-attr_1"));
        assertFalse(result.containsKey("comment"));
    }

    @Test
    void unsetSyntaxRemovesAttributeFromResult(@TempDir Path tmp) throws IOException {
        write(tmp.resolve(".asciidoctorconfig"),
                ":foo: bar",
                ":!foo:",
                ":bar: baz",
                ":bar!:");

        Map<String, Object> result = AsciidoctorConfigLoader.load(tmp);

        assertFalse(result.containsKey("foo"), "':!foo:' should remove 'foo' from result");
        assertFalse(result.containsKey("bar"), "':bar!:' should remove 'bar' from result");
    }

    @Test
    void expandsAsciidoctorconfigdirToken(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve(".asciidoctorconfig");
        write(cfg,
                ":pdf-themesdir: {asciidoctorconfigdir}/theme",
                ":mermaid-config: {asciidoctorconfigdir}/theme/mermaid-config.json");

        Map<String, Object> result = AsciidoctorConfigLoader.load(tmp);

        String configDir = tmp.toAbsolutePath().toString();
        assertEquals(configDir + "/theme", result.get("pdf-themesdir"));
        assertEquals(configDir + "/theme/mermaid-config.json", result.get("mermaid-config"));
    }

    @Test
    void doesNotExpandConfigdirAlias(@TempDir Path tmp) throws IOException {
        // {configdir} was a temporary alias that has been removed.  It should
        // now pass through verbatim (Asciidoctor will resolve any real
        // attribute references at conversion time).
        write(tmp.resolve(".asciidoctorconfig"),
                ":pdf-themesdir: {configdir}/theme");

        Map<String, Object> result = AsciidoctorConfigLoader.load(tmp);

        assertEquals("{configdir}/theme", result.get("pdf-themesdir"));
    }

    @Test
    void innerConfigOverridesOuter(@TempDir Path tmp) throws IOException {
        Path inner = Files.createDirectories(tmp.resolve("project").resolve("docs"));
        write(tmp.resolve(".asciidoctorconfig"),
                ":foo: outer",
                ":outer-only: kept");
        write(inner.resolve(".asciidoctorconfig"),
                ":foo: inner",
                ":inner-only: also-kept");

        Map<String, Object> result = AsciidoctorConfigLoader.load(inner);

        assertEquals("inner", result.get("foo"));
        assertEquals("kept", result.get("outer-only"));
        assertEquals("also-kept", result.get("inner-only"));
    }

    @Test
    void innerCanUnsetOuterAttribute(@TempDir Path tmp) throws IOException {
        Path inner = Files.createDirectories(tmp.resolve("project"));
        write(tmp.resolve(".asciidoctorconfig"), ":foo: outer-value");
        write(inner.resolve(".asciidoctorconfig"), ":!foo:");

        Map<String, Object> result = AsciidoctorConfigLoader.load(inner);

        assertFalse(result.containsKey("foo"),
                "Inner ':!foo:' should remove the outer-set attribute from the merged result");
    }

    @Test
    void recognisesAdocConfigFileExtension(@TempDir Path tmp) throws IOException {
        write(tmp.resolve(".asciidoctorconfig.adoc"), ":foo: from-adoc");
        Map<String, Object> result = AsciidoctorConfigLoader.load(tmp);
        assertEquals("from-adoc", result.get("foo"));
    }

    @Test
    void ignoresMalformedLines(@TempDir Path tmp) throws IOException {
        write(tmp.resolve(".asciidoctorconfig"),
                "this is not an attribute",
                ":valid: yes",
                "= Section title",
                "random text");

        Map<String, Object> result = AsciidoctorConfigLoader.load(tmp);

        assertEquals(1, result.size());
        assertEquals("yes", result.get("valid"));
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, java.util.Arrays.asList(lines));
    }
}

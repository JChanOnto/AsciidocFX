package com.kodedu.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExecutableResolver}.
 *
 * <p>Tests cover the deterministic, file-system based resolution tiers
 * (explicit attribute and {@code node_modules/.bin} walk-up).  The PATH
 * tier is environment-dependent and exercised indirectly by ensuring
 * resolution returns {@code null} when none of the lookup tiers succeed.
 */
class ExecutableResolverTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @BeforeEach
    @AfterEach
    void resetCaches() {
        ExecutableResolver.resetForTest();
    }

    @Test
    void resolvesExplicitAbsolutePath(@TempDir Path tmp) throws IOException {
        Path exec = createExecutable(tmp, "mytool");

        Path result = ExecutableResolver.resolve("mytool", exec.toAbsolutePath().toString(), null);

        assertNotNull(result);
        assertEquals(exec.toAbsolutePath().normalize(), result);
    }

    @Test
    void resolvesExplicitDocdirRelativePath(@TempDir Path tmp) throws IOException {
        Path docDir = Files.createDirectories(tmp.resolve("doc"));
        Path binDir = Files.createDirectories(docDir.resolve("bin"));
        Path exec = createExecutable(binDir, "mytool");

        // Use the relative spec the user would put in .asciidoctorconfig:
        String relative = IS_WINDOWS ? "bin/mytool.cmd" : "bin/mytool";
        Path result = ExecutableResolver.resolve("mytool", relative, docDir);

        assertNotNull(result);
        assertEquals(exec.toAbsolutePath().normalize(), result);
    }

    @Test
    void explicitMissingFallsThroughToNodeModules(@TempDir Path tmp) throws IOException {
        Path docDir = Files.createDirectories(tmp.resolve("doc"));
        Path nodeBin = Files.createDirectories(
                docDir.resolve("node_modules").resolve(".bin"));
        Path exec = createExecutable(nodeBin, "mmdc");

        // Explicit value points at a non-existent file; resolver must still
        // succeed via the node_modules walk-up tier.
        Path result = ExecutableResolver.resolve("mmdc", "/nonexistent/path/to/mmdc", docDir);

        assertNotNull(result);
        assertEquals(exec.toAbsolutePath().normalize(), result);
    }

    @Test
    void findsExecutableInNodeModulesAtDocDir(@TempDir Path tmp) throws IOException {
        Path docDir = Files.createDirectories(tmp.resolve("doc"));
        Path nodeBin = Files.createDirectories(
                docDir.resolve("node_modules").resolve(".bin"));
        Path exec = createExecutable(nodeBin, "mmdc");

        Path result = ExecutableResolver.resolve("mmdc", null, docDir);

        assertNotNull(result);
        assertEquals(exec.toAbsolutePath().normalize(), result);
    }

    @Test
    void walksUpToFindNodeModulesAtAncestor(@TempDir Path tmp) throws IOException {
        Path repoRoot = Files.createDirectories(tmp.resolve("repo"));
        Path nodeBin = Files.createDirectories(
                repoRoot.resolve("node_modules").resolve(".bin"));
        Path exec = createExecutable(nodeBin, "mmdc");
        Path docDir = Files.createDirectories(repoRoot.resolve("docs").resolve("guides"));

        Path result = ExecutableResolver.resolve("mmdc", null, docDir);

        assertNotNull(result);
        assertEquals(exec.toAbsolutePath().normalize(), result);
    }

    @Test
    void returnsNullWhenNothingResolves(@TempDir Path tmp) {
        // A randomly-named tool with no explicit value, no node_modules,
        // and (extremely unlikely to be on PATH or in well-known locations).
        String unique = "totally-bogus-tool-" + System.nanoTime();
        Path result = ExecutableResolver.resolve(unique, null, tmp);
        assertNull(result);
    }

    @Test
    void isAvailableReflectsResolveResult(@TempDir Path tmp) throws IOException {
        Path exec = createExecutable(tmp, "mytool");
        assertTrue(ExecutableResolver.isAvailable("mytool", exec.toAbsolutePath().toString(), null));

        String unique = "totally-bogus-tool-" + System.nanoTime();
        assertEquals(false, ExecutableResolver.isAvailable(unique, null, tmp));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsExtensionFallbackPrefersCmdOverBareName(@TempDir Path tmp) throws IOException {
        // On Windows, npm-installed CLIs ship a .cmd shim alongside a bare
        // POSIX shell script.  The resolver must prefer the .cmd.
        Path nodeBin = Files.createDirectories(tmp.resolve("node_modules").resolve(".bin"));
        Files.createFile(nodeBin.resolve("mmdc"));         // bare sh script (un-runnable on Windows)
        Path cmd = Files.createFile(nodeBin.resolve("mmdc.cmd"));

        Path result = ExecutableResolver.resolve("mmdc", null, tmp);

        assertNotNull(result);
        assertEquals(cmd.toAbsolutePath().normalize(), result);
    }

    @Test
    void knownExtensionExtsIsNonEmpty() {
        assertTrue(ExecutableResolver.knownExtensionExts().size() >= 1);
    }

    /**
     * Create an "executable" file at {@code dir/name}.  On POSIX the file
     * gets the owner/exec bit set (required by {@link Files#isExecutable}).
     * On Windows the resolver only checks {@code isRegularFile}, but to
     * exercise the extension-fallback logic we create {@code name.cmd}.
     */
    private static Path createExecutable(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file;
        if (IS_WINDOWS) {
            file = Files.createFile(dir.resolve(name + ".cmd"));
        } else {
            file = Files.createFile(dir.resolve(name));
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(file, perms);
            } catch (UnsupportedOperationException ignored) {
                // FS doesn't support POSIX perms (rare on test envs); leave as-is.
            }
        }
        return file;
    }
}

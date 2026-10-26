package io.jobclaw.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceSidecarServiceTest {
    @TempDir
    Path root;

    @Test
    void keepsWindowsEmbeddedPythonLayout() {
        assertThat(VoiceSidecarService.resolvePythonPath(root, true, null))
                .isEqualTo(root.resolve("python/python.exe"));
    }

    @Test
    void defaultsToLinuxVirtualEnvironmentPython3() {
        assertThat(VoiceSidecarService.resolvePythonPath(root, false, null))
                .isEqualTo(root.resolve("python/bin/python3"));
    }

    @Test
    void acceptsVirtualEnvironmentWithOnlyPython() throws Exception {
        Path bin = Files.createDirectories(root.resolve("python/bin"));
        Path python = Files.createFile(bin.resolve("python"));
        assertThat(VoiceSidecarService.resolvePythonPath(root, false, " ")).isEqualTo(python);
    }

    @Test
    void prefersPython3WhenBothExecutablesExist() throws Exception {
        Path bin = Files.createDirectories(root.resolve("python/bin"));
        Files.createFile(bin.resolve("python"));
        Path python3 = Files.createFile(bin.resolve("python3"));
        assertThat(VoiceSidecarService.resolvePythonPath(root, false, null)).isEqualTo(python3);
    }

    @Test
    void acceptsExplicitInterpreterOnEitherPlatform() {
        Path custom = root.resolve("custom/bin/python");
        for (boolean windows : new boolean[]{true, false}) {
            assertThat(VoiceSidecarService.resolvePythonPath(root, windows, " " + custom + " "))
                    .isEqualTo(custom.toAbsolutePath().normalize());
        }
    }

    @Test
    void doesNotUseWindowsExecutableOnLinux() throws Exception {
        Files.createDirectories(root.resolve("python"));
        Files.createFile(root.resolve("python/python.exe"));
        assertThat(VoiceSidecarService.resolvePythonPath(root, false, null))
                .isEqualTo(root.resolve("python/bin/python3"));
    }
}

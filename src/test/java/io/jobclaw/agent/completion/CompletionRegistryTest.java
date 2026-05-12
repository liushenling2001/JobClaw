package io.jobclaw.agent.completion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPassWhenRequiredFileExistsAndIsNonEmpty() throws Exception {
        Path output = tempDir.resolve("result.xlsx");
        Files.writeString(output, "xlsx");
        CompletionRegistry registry = new CompletionRegistry(tempDir.resolve("manifests"));

        registry.register("session-a", "run-a", """
                [
                  {"type":"file_exists","path":"%s"},
                  {"type":"file_non_empty","path":"%s"}
                ]
                """.formatted(escape(output), escape(output)), "create the file", 2);

        CompletionGateResult result = registry.evaluateForFinal("session-a", "run-a");

        assertTrue(result.registered());
        assertTrue(result.passed());
    }

    @Test
    void shouldFailAndLimitRetriesWhenFileIsMissing() throws Exception {
        Path output = tempDir.resolve("missing.xlsx");
        CompletionRegistry registry = new CompletionRegistry(tempDir.resolve("manifests"));
        registry.register("session-b", "run-b", """
                [{"type":"file_exists","path":"%s"}]
                """.formatted(escape(output)), "create output.xlsx", 2);

        CompletionGateResult first = registry.evaluateForFinal("session-b", "run-b");
        CompletionGateResult second = registry.evaluateForFinal("session-b", "run-b");

        assertFalse(first.passed());
        assertTrue(first.canRetry());
        assertFalse(second.passed());
        assertFalse(second.canRetry());
        assertTrue(second.toModelMessage().contains("create output.xlsx"));
    }

    @Test
    void shouldCheckManifestCompletion() throws Exception {
        Path sessionDir = tempDir.resolve("session-c");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("mf-001.json"), """
                {
                  "manifestId": "mf-001",
                  "items": {
                    "a": {"status": "done"},
                    "b": {"status": "failed"}
                  }
                }
                """);
        CompletionRegistry registry = new CompletionRegistry(tempDir);
        registry.register("session-c", "run-c", """
                [{"type":"manifest_done","manifestId":"mf-001"}]
                """, "finish manifest", 2);

        CompletionGateResult result = registry.evaluateForFinal("session-c", "run-c");

        assertTrue(result.passed());
    }

    @Test
    void shouldFailManifestWhenItemsArePending() throws Exception {
        Path sessionDir = tempDir.resolve("session-d");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("mf-002.json"), """
                {
                  "manifestId": "mf-002",
                  "items": {
                    "a": {"status": "done"},
                    "b": {"status": "pending"}
                  }
                }
                """);
        CompletionRegistry registry = new CompletionRegistry(tempDir);
        registry.register("session-d", "run-d", """
                [{"type":"manifest_done","manifestId":"mf-002"}]
                """, "process pending item", 2);

        CompletionGateResult result = registry.evaluateForFinal("session-d", "run-d");

        assertFalse(result.passed());
        assertTrue(result.failures().get(0).contains("pending=1"));
    }

    private String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}

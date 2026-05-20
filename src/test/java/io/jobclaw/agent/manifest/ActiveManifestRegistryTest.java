package io.jobclaw.agent.manifest;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.tools.ManifestTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveManifestRegistryTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldFindManagedBlockingManifestOnlyWhenPendingOrRunning() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-managed", event -> {}, "run-managed", null, null, null, null));
        ActiveManifestRegistry registry = new ActiveManifestRegistry();
        LinkedHashMap<String, ManifestTool.ManifestItem> items = new LinkedHashMap<>();
        items.put("a", new ManifestTool.ManifestItem(
                "a", "A", "pending", null, null, null, null, Instant.now(), Instant.now()));
        ManifestTool.ManifestRecord record = new ManifestTool.ManifestRecord(
                "mf-managed",
                "session-managed",
                "run-managed",
                "task",
                "fingerprint",
                null,
                null,
                "managed",
                null,
                null,
                items,
                Instant.now(),
                Instant.now()
        );

        registry.update(record);

        assertTrue(registry.findManagedBlockingState("session-managed", "run-managed").isPresent());
        ActiveManifestRegistry.ActiveManifestState state =
                registry.findManagedBlockingState("session-managed", "run-managed").orElseThrow();
        assertEquals("a", state.nextPendingItem().id());
        assertEquals("A", state.nextPendingItem().title());
    }

    @Test
    void shouldNotBlockUnmanagedManifest() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-unmanaged", event -> {}, "run-unmanaged", null, null, null, null));
        ActiveManifestRegistry registry = new ActiveManifestRegistry();
        LinkedHashMap<String, ManifestTool.ManifestItem> items = new LinkedHashMap<>();
        items.put("a", new ManifestTool.ManifestItem(
                "a", "A", "pending", null, null, null, null, Instant.now(), Instant.now()));
        ManifestTool.ManifestRecord record = new ManifestTool.ManifestRecord(
                "mf-unmanaged",
                "session-unmanaged",
                "run-unmanaged",
                "task",
                "fingerprint",
                null,
                null,
                "",
                null,
                null,
                items,
                Instant.now(),
                Instant.now()
        );

        registry.update(record);

        assertTrue(registry.findManagedBlockingState("session-unmanaged", "run-unmanaged").isEmpty());
    }

    @Test
    void shouldBlockManagedManifestUntilRequiredFinalArtifactExists() throws Exception {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-final", event -> {}, "run-final", null, null, null, null));
        ActiveManifestRegistry registry = new ActiveManifestRegistry();
        LinkedHashMap<String, ManifestTool.ManifestItem> items = new LinkedHashMap<>();
        items.put("a", new ManifestTool.ManifestItem(
                "a", "A", "done", null, null, null, null, Instant.now(), Instant.now()));
        Path finalArtifact = tempDir.resolve("results.xlsx");
        ManifestTool.ManifestRecord record = new ManifestTool.ManifestRecord(
                "mf-final",
                "session-final",
                "run-final",
                "task",
                "fingerprint",
                null,
                null,
                "managed",
                finalArtifact.toString(),
                "xlsx",
                items,
                Instant.now(),
                Instant.now()
        );

        registry.update(record);
        assertTrue(registry.findManagedBlockingState("session-final", "run-final").isPresent());

        java.nio.file.Files.writeString(finalArtifact, "xlsx");
        registry.update(record);
        assertTrue(registry.findManagedBlockingState("session-final", "run-final").isEmpty());
        assertTrue(registry.findManagedHandoffState("session-final", "run-final").isPresent());
    }

    @Test
    void shouldExposeRunningItemForManagedLoopFrame() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-running", event -> {}, "run-running", null, null, null, null));
        ActiveManifestRegistry registry = new ActiveManifestRegistry();
        LinkedHashMap<String, ManifestTool.ManifestItem> items = new LinkedHashMap<>();
        items.put("a", new ManifestTool.ManifestItem(
                "a", "A", "done", "D:/out/a.json", null, null, null, Instant.now(), Instant.now()));
        items.put("b", new ManifestTool.ManifestItem(
                "b", "B", "running", null, null, "reading", null, Instant.now(), Instant.now()));
        ManifestTool.ManifestRecord record = new ManifestTool.ManifestRecord(
                "mf-running",
                "session-running",
                "run-running",
                "task",
                "fingerprint",
                "{\"columns\":[\"title\"]}",
                "D:/out/results.jsonl",
                "managed",
                null,
                null,
                items,
                Instant.now(),
                Instant.now()
        );

        registry.update(record);

        ActiveManifestRegistry.ActiveManifestState state =
                registry.findManagedBlockingState("session-running", "run-running").orElseThrow();
        assertEquals("b", state.runningItem().id());
        assertEquals("{\"columns\":[\"title\"]}", state.schema());
        assertEquals("D:/out/results.jsonl", state.artifactPath());
        assertTrue(registry.formatForPrompt("session-running", "run-running")
                .contains("currentRunningItem: b | running | B"));
    }

    @Test
    void shouldExposeMultipleRunningAndPendingItemsForManagedRunnerSelection() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-queue", event -> {}, "run-queue", null, null, null, null));
        ActiveManifestRegistry registry = new ActiveManifestRegistry();
        LinkedHashMap<String, ManifestTool.ManifestItem> items = new LinkedHashMap<>();
        items.put("a", new ManifestTool.ManifestItem(
                "a", "A", "running", null, null, null, null, Instant.now(), Instant.now()));
        items.put("b", new ManifestTool.ManifestItem(
                "b", "B", "running", null, null, null, null, Instant.now(), Instant.now()));
        items.put("c", new ManifestTool.ManifestItem(
                "c", "C", "pending", null, null, null, null, Instant.now(), Instant.now()));
        items.put("d", new ManifestTool.ManifestItem(
                "d", "D", "pending", null, null, null, null, Instant.now(), Instant.now()));
        ManifestTool.ManifestRecord record = new ManifestTool.ManifestRecord(
                "mf-queue",
                "session-queue",
                "run-queue",
                "task",
                "fingerprint",
                null,
                null,
                "managed",
                null,
                null,
                items,
                Instant.now(),
                Instant.now()
        );

        registry.update(record);

        ActiveManifestRegistry.ActiveManifestState state =
                registry.findManagedBlockingState("session-queue", "run-queue").orElseThrow();
        assertEquals(2, state.runningQueue().size());
        assertEquals(2, state.pendingQueue().size());
        assertEquals("a", state.runningQueue().get(0).id());
        assertEquals("c", state.pendingQueue().get(0).id());
    }

}

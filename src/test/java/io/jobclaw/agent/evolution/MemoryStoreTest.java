package io.jobclaw.agent.evolution;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryStoreTest {
    @TempDir Path root;

    @Test
    void separatesProjectAndAgentMemoriesButSharesExplicitUserPreferences() {
        MemoryStore store = new MemoryStore(root.toString());
        MemoryScope a = new MemoryScope(root.resolve("a").toString(), "writer");
        MemoryScope b = new MemoryScope(root.resolve("b").toString(), "writer");
        store.addEntry("PDF project-a secret", .9, List.of(), "user_explicit", "project", a);
        store.addEntry("PDF writer-only secret", .9, List.of(), "user_explicit", "agent", a);
        store.addEntry("Prefer Chinese replies", .9, List.of("preference"), "user_explicit", "user", a);

        assertEquals(3, store.getEntries(a).size());
        assertEquals(1, store.getEntries(b).size());
        assertEquals(2, store.getEntries(new MemoryScope(a.workspace(), "reviewer")).size());
        String prompt = store.getMemoryContext("PDF", 2048, b);
        assertFalse(prompt.contains("secret"));
        assertTrue(prompt.contains("Prefer Chinese replies"));
    }

    @Test
    void refusesCrossWorkspaceUpdateAndForgetAndPersistsAnExplicitRevision() {
        MemoryStore store = new MemoryStore(root.toString());
        MemoryScope a = new MemoryScope(root.resolve("a").toString(), "default");
        MemoryScope b = new MemoryScope(root.resolve("b").toString(), "default");
        MemoryEntry entry = store.addEntry("PDF old format", .8, List.of(), "user_explicit", "project", a);
        assertNull(store.updateEntry(entry.getId(), "wrong", null, b));
        assertNull(store.removeEntry(entry.getId(), b));
        store.updateEntry(entry.getId(), "PDF new format", null, a);

        MemoryStore reloaded = new MemoryStore(root.toString());
        MemoryEntry updated = reloaded.getEntries(a).getFirst();
        assertEquals(entry.getId(), updated.getId());
        assertEquals("PDF new format", updated.getContent());
        assertEquals("PDF old format", updated.getRevisions().getFirst().content());
        assertFalse(reloaded.getMemoryContext("PDF", 2048, a).contains("old format"));
    }

    @Test
    void unrelatedMemoriesDoNotFillTheBudgetOrGainRelevanceFromExposure() {
        MemoryStore store = new MemoryStore(root.toString());
        store.addEntry("PDF redaction workflow", 1, List.of(), "session_summary");
        assertEquals("", store.getMemoryContext("hello", 2048));
        MemoryEntry entry = store.getEntries().getFirst();
        double before = entry.computeScore();
        entry.setAccessCount(10000);
        assertEquals(before, entry.computeScore());
        assertTrue(store.getMemoryContext("PDF", 2048).contains("PDF redaction"));
    }

    @Test
    void legacyRecordsAreConservativelyBoundToOriginalWorkspace() throws Exception {
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("memory/MEMORIES.json"), """
                [{"id":"legacy","content":"PDF old project","importance":0.9,"tags":["project"],"source":"session_summary"},
                 {"id":"pref","content":"Chinese replies","importance":0.9,"tags":["user","preference"],"source":"user_explicit"}]
                """);
        MemoryStore store = new MemoryStore(root.toString());
        assertEquals(2, store.getEntries(store.defaultScope()).size());
        var other = new MemoryScope(root.resolve("other").toString(), "default");
        assertEquals(List.of("pref"), store.getEntries(other).stream().map(MemoryEntry::getId).toList());
    }

    @Test
    void concurrentRememberAndRecallNeverOverwriteEachOther() throws Exception {
        MemoryStore store = new MemoryStore(root.toString());
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<Void>> work = IntStream.range(0, 60).mapToObj(i -> (Callable<Void>) () -> {
                store.addEntry("PDF fact " + i, .8, List.of(), "user_explicit");
                store.getMemoryContext("PDF", 1024);
                return null;
            }).toList();
            for (var result : executor.invokeAll(work)) {
                result.get();
            }
        }
        MemoryStore reloaded = new MemoryStore(root.toString());
        assertEquals(60, reloaded.getEntries().size());
        assertEquals(60, reloaded.getEntries().stream().map(MemoryEntry::getContent).distinct().count());
        try (var files = Files.list(root.resolve("memory"))) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void readSnapshotsCannotMutateTheStore() {
        MemoryStore store = new MemoryStore(root.toString());
        store.addEntry("original", .8, List.of(), "user_explicit");
        store.getEntries().getFirst().setContent("mutated without save");
        assertEquals("original", store.getEntries().getFirst().getContent());
    }

    @Test
    void backgroundScopeUsesWorkspaceRegistryNotTheUnrelatedCurrentSession() {
        WorkspaceRegistry registry = mock(WorkspaceRegistry.class);
        when(registry.resolveWorkingDirectory("a")).thenReturn(root.resolve("a").toString());
        MemoryStore store = new MemoryStore(root.toString(), registry);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "b", null, null, null, null, null, null, null, root.resolve("b").toString()));
        try {
            assertEquals(new MemoryScope(root.resolve("a").toString(), "default"), store.scopeFor("a"));
            assertEquals(new MemoryScope(root.resolve("b").toString(), "default"), store.scopeFor("b"));
        } finally {
            AgentExecutionContext.clear();
        }
    }

    @Test
    void dailyNotesAreScopedAndOldMixedNotesAreNotAutoInjected() throws Exception {
        MemoryStore store = new MemoryStore(root.toString());
        MemoryScope a = store.defaultScope();
        MemoryScope b = new MemoryScope(root.resolve("b").toString(), "default");
        store.appendToday("PDF project-a notes", a);
        store.appendToday("PDF project-b notes", b);
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        Path legacy = root.resolve("memory").resolve(date.substring(0, 6)).resolve(date + ".md");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "PDF old mixed notes");
        assertTrue(store.getMemoryContext("PDF", 2048, a).contains("project-a notes"));
        assertFalse(store.getMemoryContext("PDF", 2048, a).contains("project-b notes"));
        assertFalse(store.getMemoryContext("PDF", 2048, b).contains("project-a notes"));
        assertFalse(store.getMemoryContext("PDF", 2048, a).contains("old mixed notes"));
        assertEquals("", store.getMemoryContext("hello", 2048, b));
        store.writeLongTerm("PDF legacy project-a");
        assertFalse(store.getMemoryContext("PDF", 2048, b).contains("legacy"));
    }

    @Test
    void failedWriteDoesNotClaimTheNewMemoryWasSaved() throws Exception {
        MemoryStore store = new MemoryStore(root.toString());
        Path target = root.resolve("memory/MEMORIES.json");
        Files.createDirectory(target);
        Files.writeString(target.resolve("block"), "prevent replacement");
        assertThrows(IllegalStateException.class, () -> store.addEntry("unsaved", .8, List.of(), "user_explicit"));
        assertTrue(store.getEntries().isEmpty());
    }
}

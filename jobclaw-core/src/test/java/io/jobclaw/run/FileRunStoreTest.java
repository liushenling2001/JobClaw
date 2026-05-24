package io.jobclaw.run;

import io.jobclaw.agent.ExecutionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRunStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsRunEventsAndArtifacts() throws Exception {
        FileRunStore store = new FileRunStore(tempDir);
        RunRecord record = new RunRecord();
        record.setRunId("run-test");
        record.setSessionKey("session-test");
        record.setStatus(RunStatus.RUNNING);
        record.setTask("test task");
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());

        store.save(record);
        store.appendEvent("run-test", new ExecutionEvent(
                "session-test",
                ExecutionEvent.EventType.TOOL_START,
                "tool started",
                null,
                "run-test",
                null,
                "assistant",
                "Assistant"
        ));
        store.saveArtifacts("run-test", List.of(tempDir.resolve("out.txt").toString()));

        RunRecord loaded = store.get("run-test").orElseThrow();
        assertEquals("session-test", loaded.getSessionKey());
        assertEquals(RunStatus.RUNNING, loaded.getStatus());
        assertEquals(1, store.readEvents("run-test", 10).size());
        assertEquals(1, store.readArtifacts("run-test").size());
        assertTrue(store.list(10).stream().anyMatch(run -> "run-test".equals(run.getRunId())));
    }
}

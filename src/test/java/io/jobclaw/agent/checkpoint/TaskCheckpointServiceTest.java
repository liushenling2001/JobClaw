package io.jobclaw.agent.checkpoint;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCheckpointServiceTest {

    @Test
    void shouldSaveAndReloadResumableWorklistCheckpoint() throws Exception {
        Path root = Files.createTempDirectory("jobclaw-checkpoints");
        TaskCheckpointStore store = new FileTaskCheckpointStore(root.toString());
        TaskCheckpointService service = new TaskCheckpointService(store);

        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "批量审查目录下所有 pdf");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "batch-files");
        run.upsertPlannedSubtask("a.pdf", "a.pdf", Map.of("source", "test"));
        run.upsertPlannedSubtask("b.pdf", "b.pdf", Map.of("source", "test"));
        run.markSubtaskCompleted("a.pdf", "已完成", true, Map.of("source", "test"));

        service.save(run);

        TaskCheckpoint checkpoint = store.latest("session-a").orElseThrow();
        assertTrue(checkpoint.pendingSubtasks() > 0);
        assertTrue(service.latestResumable("session-a", "批量审查目录下所有 pdf", TaskPlanningMode.WORKLIST).isPresent());

        String guidance = service.buildResumeGuidance(checkpoint);
        assertTrue(guidance.contains("已完成子任务"));
        assertTrue(guidance.contains("待继续子任务"));
        assertTrue(guidance.contains("不要重复已完成子任务"));
    }

    @Test
    void shouldIgnoreDifferentTaskInputWhenResuming() throws Exception {
        Path root = Files.createTempDirectory("jobclaw-checkpoints");
        TaskCheckpointStore store = new FileTaskCheckpointStore(root.toString());
        TaskCheckpointService service = new TaskCheckpointService(store);

        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "批量审查目录下所有 pdf");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "batch-files");
        run.upsertPlannedSubtask("a.pdf", "a.pdf", Map.of("source", "test"));
        service.save(run);

        assertFalse(service.latestResumable("session-a", "另外一个任务", TaskPlanningMode.WORKLIST).isPresent());
    }
}

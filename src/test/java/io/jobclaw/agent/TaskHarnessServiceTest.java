package io.jobclaw.agent;

import io.jobclaw.agent.artifact.FileRunArtifactStore;
import io.jobclaw.agent.checkpoint.TaskCheckpointService;
import io.jobclaw.agent.checkpoint.TaskCheckpointStore;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.agent.planning.PlanReviewAction;
import io.jobclaw.agent.planning.PlanReviewDecision;
import io.jobclaw.agent.planning.PlanStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHarnessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldTrackTransitionsAndCompletion() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-1", "fix failing test", null);

        service.transition(run, TaskHarnessPhase.ACT, "running", "act", "Running task", java.util.Map.of(), null);
        service.complete(run, true, "Task completed", null);

        List<TaskHarnessStep> steps = run.getSteps();
        assertEquals(TaskHarnessPhase.FINISH, run.getCurrentPhase());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.PLAN));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.ACT));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == TaskHarnessPhase.FINISH));
        assertNotNull(run.getCompletedAt());
    }

    @Test
    void shouldStoreAndClearFailureStateAcrossCompletion() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-3", "fix failing test", null);
        TaskHarnessFailure failure = new TaskHarnessFailure(
                TaskHarnessFailureKind.TOOL_FAILURE,
                "Shell command failed",
                "toolName=shell, request=mvn test"
        );

        service.recordFailure(run, failure, null);

        assertEquals(failure, run.getLastFailure());

        service.complete(run, true, "Recovered", null);

        assertNull(run.getLastFailure());
    }

    @Test
    void shouldTrackPendingSubtasksUntilCompleted() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-4", "review multiple files", null);

        service.planSubtask(run.getRunId(), "a.txt", "File A", java.util.Map.of(), null);
        service.planSubtask(run.getRunId(), "b.txt", "File B", java.util.Map.of(), null);
        service.startSubtask(run.getRunId(), "a.txt", "File A", "spawn-1", java.util.Map.of(), null);
        service.completeSubtask(run.getRunId(), "a.txt", "done", true, java.util.Map.of(), null);

        assertEquals(1, run.getPendingSubtaskCount());
        assertEquals(2, run.getSubtasks().size());
        assertTrue(run.getSubtasks().stream().anyMatch(subtask -> subtask.id().equals("a.txt")
                && subtask.status() == TaskHarnessSubtaskStatus.COMPLETED));
    }

    @Test
    void shouldRecordPlanExecutionSnapshotFromToolEvents() {
        TaskHarnessService service = new TaskHarnessService();
        TaskHarnessRun run = service.startRun("session-a", "run-5", "根据多个文件完善目标文件", null);
        service.initializePlan(run, List.of(
                new PlanStep("inspect-inputs", "确认输入文件", "文件已定位"),
                new PlanStep("process-sources", "提炼参考文件", "形成摘要"),
                new PlanStep("update-target", "更新目标文件", "文件已写入")
        ), null);

        service.wrapEventCallback(run, null).accept(new ExecutionEvent(
                "session-a",
                ExecutionEvent.EventType.TOOL_OUTPUT,
                "Successfully wrote to target.docx",
                java.util.Map.of("toolName", "write_file"),
                run.getRunId(),
                null,
                "tool",
                "write_file"
        ));

        String snapshot = service.buildPlanExecutionSnapshot(run);
        assertTrue(snapshot.contains("update-target [COMPLETED]"));
        assertTrue(snapshot.contains("Successfully wrote to target.docx"));
    }

    @Test
    void shouldPersistStepHandoffAsRunArtifact() {
        TaskHarnessService service = new TaskHarnessService(
                new TaskCheckpointService(new NoopCheckpointStore()),
                new ActiveExecutionRegistry(),
                new FileRunArtifactStore(tempDir.toString())
        );
        TaskHarnessRun run = service.startRun("session-a", "run-a", "完善报告", event -> {});
        service.initializePlan(run, List.of(
                new PlanStep("process-sources", "提炼参考材料", "形成简短可用要点"),
                new PlanStep("update-target", "更新目标文件", "目标文件已落地")
        ), event -> {});

        String context = service.buildStepRuntimeContext(run);
        assertTrue(context.contains("stepId: process-sources"));

        service.completeCurrentPlanStep(run, "已提炼参考材料：A、B、C。", event -> {});

        String snapshot = run.planExecutionSnapshot();
        assertTrue(snapshot.contains("process-sources [COMPLETED]"));
        assertTrue(snapshot.contains("artifacts:"));
        assertTrue(Files.exists(tempDir.resolve("run-a").resolve("state").resolve("artifacts.json")));
    }

    @Test
    void shouldSplitCurrentStepWhenPlanReviewRequestsIt() {
        TaskHarnessService service = new TaskHarnessService(
                new TaskCheckpointService(new NoopCheckpointStore()),
                new ActiveExecutionRegistry(),
                new FileRunArtifactStore(tempDir.toString())
        );
        TaskHarnessRun run = service.startRun("session-a", "run-b", "根据多个文件完善报告", event -> {});
        service.initializePlan(run, List.of(
                new PlanStep("process-sources", "处理多个参考文件", "形成简短可用要点"),
                new PlanStep("update-target", "更新目标文件", "目标文件已落地")
        ), event -> {});

        service.recordPlanReviewDecision(
                run,
                PlanReviewDecision.of(PlanReviewAction.SPLIT_STEP, "step too large", List.of("split")),
                event -> {}
        );

        String snapshot = run.planExecutionSnapshot();
        assertTrue(snapshot.contains("process-sources-collect [RUNNING]"));
        assertTrue(snapshot.contains("process-sources-summarize [PENDING]"));
        assertTrue(snapshot.contains("process-sources-handoff [PENDING]"));
    }

    private static class NoopCheckpointStore implements TaskCheckpointStore {
        @Override
        public void save(io.jobclaw.agent.checkpoint.TaskCheckpoint checkpoint) {
        }

        @Override
        public Optional<io.jobclaw.agent.checkpoint.TaskCheckpoint> latest(String sessionId) {
            return Optional.empty();
        }
    }
}

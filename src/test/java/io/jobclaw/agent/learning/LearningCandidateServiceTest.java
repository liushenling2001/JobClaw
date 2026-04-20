package io.jobclaw.agent.learning;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessFailure;
import io.jobclaw.agent.TaskHarnessFailureKind;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningCandidateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldNotRecordPositiveCandidatesForSingleSuccessfulRun() {
        LearningCandidateService service = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.toString())
        );
        TaskHarnessRun run = successfulBatchRun("run-a");

        service.recordSuccessfulRun(run);

        assertTrue(service.listPending().isEmpty());
    }

    @Test
    void shouldNotRecordPositiveCandidatesForSingleRepairedSuccessfulRun() {
        LearningCandidateService service = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.toString())
        );
        TaskHarnessRun run = successfulBatchRun("run-a");
        run.incrementRepairAttempts();

        service.recordSuccessfulRun(run);

        assertTrue(service.listPending().isEmpty());
    }

    @Test
    void shouldNotDuplicateCandidatesForSameRun() {
        LearningCandidateService service = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.toString())
        );
        TaskHarnessRun run = successfulBatchRun("run-a");

        service.recordSuccessfulRun(run);
        service.recordSuccessfulRun(run);

        assertTrue(service.listPending().isEmpty());
    }

    @Test
    void shouldIgnorePureAnswerRuns() {
        LearningCandidateService service = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.toString())
        );
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-answer", "解释上下文压缩");
        run.setPlanningMode(TaskPlanningMode.DIRECT, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.DIRECT,
                DeliveryType.ANSWER,
                List.of(),
                List.of(),
                false,
                true,
                true,
                List.of("usable_answer")
        ));
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "memory",
                Map.of("eventType", "TOOL_START", "toolName", "memory"));
        run.complete(true);

        service.recordSuccessfulRun(run);

        assertTrue(service.listPending().isEmpty());
    }

    @Test
    void shouldRecordNegativeLessonForFailedRun() {
        LearningCandidateService service = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.toString())
        );
        TaskHarnessRun run = failedBatchRun("run-failed");

        service.recordFailedRun(run, "pending work remained");

        List<LearningCandidate> pending = service.listPending();
        assertEquals(1, pending.size());
        LearningCandidate candidate = pending.get(0);
        assertEquals(LearningCandidateType.NEGATIVE_LESSON, candidate.getType());
        assertTrue(candidate.getProposal().contains("Task failed"));
        assertTrue(candidate.getProposal().contains("pending work remained"));
        assertTrue(candidate.getMetadata().containsKey("toolSequence"));
    }

    @Test
    void shouldNotDuplicateNegativeLessonForSameRun() {
        LearningCandidateService service = new LearningCandidateService(
                new FileLearningCandidateStore(tempDir.toString())
        );
        TaskHarnessRun run = failedBatchRun("run-failed");

        service.recordFailedRun(run, "first");
        service.recordFailedRun(run, "second");

        assertEquals(1, service.listPending().size());
    }

    private TaskHarnessRun successfulBatchRun(String runId) {
        TaskHarnessRun run = new TaskHarnessRun("session-a", runId, "批量审查目录下所有 PDF 文件");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.WORKLIST,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                true,
                true,
                false,
                List.of("worklist")
        ));
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "list",
                Map.of("eventType", "TOOL_START", "toolName", "list_dir"));
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "subtasks",
                Map.of("eventType", "TOOL_START", "toolName", "subtasks"));
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "spawn",
                Map.of("eventType", "TOOL_START", "toolName", "spawn"));
        run.markSubtaskCompleted("a.pdf", "ok", true, Map.of());
        run.complete(true);
        return run;
    }

    private TaskHarnessRun failedBatchRun(String runId) {
        TaskHarnessRun run = new TaskHarnessRun("session-a", runId, "批量审查目录下所有 PDF 文件");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.WORKLIST,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                true,
                true,
                false,
                List.of("worklist")
        ));
        run.addStep(TaskHarnessPhase.ACT, "event", "tool_start", "list",
                Map.of("eventType", "TOOL_START", "toolName", "list_dir"));
        run.recordFailure(new TaskHarnessFailure(
                TaskHarnessFailureKind.VERIFICATION_FAILURE,
                "worklist incomplete",
                "pending subtasks remain"
        ));
        run.complete(false);
        return run;
    }
}

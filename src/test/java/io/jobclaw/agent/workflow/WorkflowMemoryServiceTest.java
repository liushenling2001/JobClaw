package io.jobclaw.agent.workflow;

import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessSubtaskStatus;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.experience.ExperienceMemoryType;
import io.jobclaw.agent.experience.FileExperienceMemoryStore;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRecordAndRecallSuccessfulWorkflow() {
        WorkflowMemoryService service = new WorkflowMemoryService(new FileWorkflowMemoryStore(tempDir.toString()));
        TaskHarnessRun run = new TaskHarnessRun(
                "session-a",
                "run-a",
                "请批量审查目录下所有 PDF 文件"
        );
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
        run.addStep(io.jobclaw.agent.TaskHarnessPhase.ACT, "event", "tool_start", "list",
                Map.of("eventType", "TOOL_START", "toolName", "list_dir"));
        run.addStep(io.jobclaw.agent.TaskHarnessPhase.ACT, "event", "tool_start", "subtasks",
                Map.of("eventType", "TOOL_START", "toolName", "subtasks"));
        run.addStep(io.jobclaw.agent.TaskHarnessPhase.ACT, "event", "tool_start", "spawn",
                Map.of("eventType", "TOOL_START", "toolName", "spawn"));
        run.markSubtaskCompleted("a.pdf", "ok", true, Map.of());
        run.complete(true);

        service.recordSuccess(run);

        var found = service.findRelevant(
                "批量审查 PDF 文件",
                TaskPlanningMode.WORKLIST,
                run.getDoneDefinition()
        );

        assertTrue(found.isPresent());
        assertTrue(service.buildGuidance(found.get()).contains("[Relevant Prior Workflow Reference]"));
        assertTrue(service.buildGuidance(found.get()).contains("list_dir"));
        assertTrue(found.get().getConfidence() >= 0.45);
    }

    @Test
    void shouldAutoPromoteRepeatedSuccessfulWorkflowToExperienceMemory() {
        FileExperienceMemoryStore experienceStore = new FileExperienceMemoryStore(
                tempDir.resolve("experience").toString()
        );
        WorkflowMemoryService service = new WorkflowMemoryService(
                new FileWorkflowMemoryStore(tempDir.resolve("workflows").toString()),
                new ExperienceMemoryService(experienceStore)
        );

        service.recordSuccess(successfulBatchRun("run-a"));
        service.recordSuccess(successfulBatchRun("run-b"));
        service.recordSuccess(successfulBatchRun("run-c"));

        var memories = experienceStore.list();
        assertEquals(1, memories.size());
        assertEquals(ExperienceMemoryType.WORKFLOW_EXPERIENCE, memories.get(0).getType());
        assertEquals("workflow_auto_promoted", memories.get(0).getMetadata().get("source"));
        assertEquals(3, memories.get(0).getMetadata().get("successCount"));
        assertTrue(memories.get(0).getProposal().contains("repeated successful similar runs"));
    }

    private TaskHarnessRun successfulBatchRun(String runId) {
        TaskHarnessRun run = new TaskHarnessRun(
                "session-a",
                runId,
                "请批量审查目录下所有 PDF 文件"
        );
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
        run.addStep(io.jobclaw.agent.TaskHarnessPhase.ACT, "event", "tool_start", "list",
                Map.of("eventType", "TOOL_START", "toolName", "list_dir"));
        run.addStep(io.jobclaw.agent.TaskHarnessPhase.ACT, "event", "tool_start", "subtasks",
                Map.of("eventType", "TOOL_START", "toolName", "subtasks"));
        run.addStep(io.jobclaw.agent.TaskHarnessPhase.ACT, "event", "tool_start", "spawn",
                Map.of("eventType", "TOOL_START", "toolName", "spawn"));
        run.markSubtaskCompleted("a.pdf", "ok", true, Map.of());
        run.complete(true);
        return run;
    }
}

package io.jobclaw.agent;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.planning.PlanStep;
import io.jobclaw.agent.planning.TaskPlanningMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsetRuntimePolicyTest {

    private final ToolsetRuntimePolicy policy = new ToolsetRuntimePolicy();

    @Test
    void shouldAlwaysPairCommandExecutionWithWriteFile() {
        Set<String> tools = policy.requiredToolsFor(null);

        assertTrue(tools.contains("run_command"));
        assertTrue(tools.contains("exec"));
        assertTrue(tools.contains("write_file"));
    }

    @Test
    void shouldRequireWriteToolsForArtifactStepEvenWhenRunIsWorklist() {
        TaskHarnessRun run = new TaskHarnessRun("session", "run-1", "总结目录 PDF 并生成 Word");
        run.setPlanningMode(TaskPlanningMode.WORKLIST, "batch with final artifact");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.WORKLIST,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                true,
                true,
                false,
                List.of()
        ));
        run.initializePlanExecution(List.of(
                new PlanStep("execute-worklist", "逐个处理文件", "所有子任务完成"),
                new PlanStep("produce-artifact", "生成最终 Word 报告", "目标文件已写入")
        ));
        run.getPlanExecutionState().completeCurrentStep("items complete", null, null);

        Set<String> tools = policy.requiredToolsFor(run);

        assertTrue(tools.contains("subtasks"));
        assertTrue(tools.contains("spawn"));
        assertTrue(tools.contains("write_file"));
        assertTrue(tools.contains("edit_file"));
        assertTrue(tools.contains("append_file"));
    }

    @Test
    void shouldRequireWriteToolsForPatchDelivery() {
        TaskHarnessRun run = new TaskHarnessRun("session", "run-2", "修改报告");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.PHASED,
                DeliveryType.PATCH,
                List.of(),
                List.of(),
                false,
                false,
                false,
                List.of()
        ));

        Set<String> tools = policy.requiredToolsFor(run);

        assertTrue(tools.contains("read_file"));
        assertTrue(tools.contains("write_file"));
        assertTrue(tools.contains("run_command"));
        assertTrue(tools.contains("exec"));
    }
}

package io.jobclaw.agent.completion;

import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.planning.PlanStep;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskCompletionControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRepairRetryableFailedSubtaskBeforeFinalSummary() {
        Config config = Config.defaultConfig();
        config.getAgent().setMaxSubtaskRepairAttempts(1);
        TaskHarnessRun run = worklistRun();
        run.markSubtaskCompleted("a.pdf", "timeout", false, Map.of(
                "failureType", "timeout",
                "retryable", true,
                "retryCount", 1
        ));

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "最终汇总：a.pdf 失败。", null);

        assertEquals(TaskCompletionDecision.Status.REPAIR, decision.status());
        assertEquals(List.of("failed_subtasks_retryable"), decision.missingRequirements());
    }

    @Test
    void shouldAllowFinalSummaryWhenRetryLimitReached() {
        Config config = Config.defaultConfig();
        config.getAgent().setMaxSubtaskRepairAttempts(1);
        TaskHarnessRun run = worklistRun();
        run.markSubtaskCompleted("a.pdf", "timeout", false, Map.of(
                "failureType", "timeout",
                "retryable", true,
                "retryCount", 2
        ));

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "最终汇总：a.pdf 失败，原因 timeout。", null);

        assertEquals(TaskCompletionDecision.Status.COMPLETE, decision.status());
    }

    @Test
    void shouldContinuePendingSubtasksEvenWhenWorklistWasOptional() {
        Config config = Config.defaultConfig();
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-child", "child batch task");
        run.setPlanningMode(TaskPlanningMode.DIRECT, "child_contract");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.DIRECT,
                DeliveryType.BATCH_RESULTS,
                List.of(),
                List.of(),
                false,
                true,
                false,
                List.of()
        ));
        run.upsertPlannedSubtask("nested-a", "Nested A", Map.of());

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "最终汇总：处理中。", null);

        assertEquals(TaskCompletionDecision.Status.CONTINUE, decision.status());
        assertEquals(List.of("pending_subtasks"), decision.missingRequirements());
    }

    @Test
    void shouldCompleteFileArtifactFromToolOutputPath() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        Path artifact = tempDir.resolve("研究综述.docx");
        Files.writeString(artifact, "docx-placeholder");
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "生成综述 Word");
        run.setPlanningMode(TaskPlanningMode.PHASED, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.PHASED,
                DeliveryType.FILE_ARTIFACT,
                List.of(),
                List.of(),
                false,
                false,
                true,
                List.of("artifact_exists")
        ));
        run.addStep(
                TaskHarnessPhase.OBSERVE,
                "event",
                "tool_output",
                "Document saved to: " + artifact,
                Map.of("eventType", "TOOL_OUTPUT", "toolName", "run_command")
        );

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "已保存。", null);

        assertEquals(TaskCompletionDecision.Status.COMPLETE, decision.status());
    }

    @Test
    void shouldIgnoreDirectoryListingHeaderWhenExtractingArtifactPath() throws Exception {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        Path artifact = tempDir.resolve("研究生招生质量研究文献综合摘要.docx");
        Files.writeString(artifact, "docx-placeholder");
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "生成综述 Word");
        run.setPlanningMode(TaskPlanningMode.PHASED, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.PHASED,
                DeliveryType.FILE_ARTIFACT,
                List.of(),
                List.of(),
                false,
                false,
                true,
                List.of("artifact_exists")
        ));
        run.addStep(
                TaskHarnessPhase.OBSERVE,
                "event",
                "tool_output",
                tempDir + " 的目录\\n\\n2026/04/23  09:23            15,623 研究生招生质量研究文献综合摘要.docx",
                Map.of("eventType", "TOOL_OUTPUT", "toolName", "run_command")
        );
        run.addStep(
                TaskHarnessPhase.OBSERVE,
                "event",
                "tool_output",
                "Document saved to: " + artifact,
                Map.of("eventType", "TOOL_OUTPUT", "toolName", "run_command")
        );

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "已保存。", null);

        assertEquals(TaskCompletionDecision.Status.COMPLETE, decision.status());
    }

    @Test
    void shouldRequireArtifactUnderPlannedOutputDirectory() throws Exception {
        Config config = Config.defaultConfig();
        Path workspace = tempDir.resolve("workspace");
        Path targetDir = tempDir.resolve("招生");
        Files.createDirectories(workspace);
        Files.createDirectories(targetDir);
        config.getAgent().setWorkspace(workspace.toString());
        Path workspaceArtifact = workspace.resolve("研究生招生质量研究文献综合摘要.docx");
        Path targetArtifact = targetDir.resolve("研究生招生质量研究文献综合摘要.docx");
        Files.writeString(workspaceArtifact, "wrong-location");
        Files.writeString(targetArtifact, "right-location");
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "保存 Word 到指定目录");
        run.setPlanningMode(TaskPlanningMode.PHASED, "test");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.PHASED,
                DeliveryType.FILE_ARTIFACT,
                List.of(),
                List.of(targetDir.toString()),
                List.of(),
                false,
                false,
                true,
                List.of("artifact_exists")
        ));
        run.addStep(
                TaskHarnessPhase.OBSERVE,
                "event",
                "tool_output",
                "Document saved to: " + workspaceArtifact,
                Map.of("eventType", "TOOL_OUTPUT", "toolName", "run_command")
        );
        run.addStep(
                TaskHarnessPhase.OBSERVE,
                "event",
                "tool_output",
                "Document saved to: " + targetArtifact,
                Map.of("eventType", "TOOL_OUTPUT", "toolName", "run_command")
        );

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "已保存。", null);

        assertEquals(TaskCompletionDecision.Status.COMPLETE, decision.status());
        assertEquals(List.of(targetArtifact.toString()), decision.status() == TaskCompletionDecision.Status.COMPLETE
                ? new TaskCompletionController(config).collectState(run, run.getDoneDefinition(), "已保存。", null).artifactPaths().stream()
                .filter(path -> path.equals(targetArtifact.toString()))
                .toList()
                : List.of());
    }

    @Test
    void shouldContinueWhenNonDirectPlanStillHasUnfinishedSteps() {
        Config config = Config.defaultConfig();
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "总结 D:\\DOC\\招生 下所有 PDF 并生成 Word");
        run.setPlanningMode(TaskPlanningMode.PHASED, "agent_generated_plan");
        run.setDoneDefinition(new DoneDefinition(
                TaskPlanningMode.PHASED,
                DeliveryType.FILE_ARTIFACT,
                List.of(),
                List.of(TaskHarnessPhase.ACT),
                false,
                true,
                true,
                List.of("artifact_exists")
        ));
        run.initializePlanExecution(List.of(
                new PlanStep("list-pdfs", "列出 PDF 文件", "识别所有 PDF"),
                new PlanStep("read-content", "读取 PDF 内容", "提取文本"),
                new PlanStep("write-word", "生成 Word", "保存 docx")
        ));
        run.getPlanExecutionState().startCurrentStep();
        run.getPlanExecutionState().completeCurrentStep("识别到 6 个 PDF，准备进入下一步 read-content。", Map.of(), null);

        TaskCompletionDecision decision = new TaskCompletionController(config)
                .evaluate(run, run.getDoneDefinition(), "Step `list-pdfs` 完成。准备进入下一步 `read-content`。", null);

        assertEquals(TaskCompletionDecision.Status.CONTINUE, decision.status());
        assertEquals(List.of("unfinished_plan_steps"), decision.missingRequirements());
    }

    private TaskHarnessRun worklistRun() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-a", "批量审查 PDF");
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
        return run;
    }
}

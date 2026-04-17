package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChangeCompletionVerifierRuleTest {

    private final FileChangeCompletionVerifierRule rule = new FileChangeCompletionVerifierRule();

    @Test
    void shouldRejectFileChangeResponseThatStillPlansMoreWork() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "修改 report.md 文件内容");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "edit_file",
                Map.of(
                        "eventType", "TOOL_START",
                        "toolName", "edit_file",
                        "request", "{\"path\":\"reports/report.md\",\"oldText\":\"a\",\"newText\":\"b\"}"
                )
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                "我先修改这个文件，接下来继续整理内容。",
                null
        );

        assertFalse(result.success());
    }

    @Test
    void shouldAcceptFileChangeResponseThatMentionsChangedPath() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "修改 report.md 文件内容");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "edit_file",
                Map.of(
                        "eventType", "TOOL_START",
                        "toolName", "edit_file",
                        "request", "{\"path\":\"reports/report.md\",\"oldText\":\"a\",\"newText\":\"b\"}"
                )
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                "已更新 reports/report.md，修正了摘要部分并保留原有结构。",
                null
        );

        assertTrue(result.success());
    }
}

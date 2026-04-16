package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSummaryVerifierRuleTest {

    private final DocumentSummaryVerifierRule rule = new DocumentSummaryVerifierRule();

    @Test
    void shouldAcceptSubstantivePdfSummaryWithOptionalQuestion() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-1", "总结这个 PDF 文件前 3 页的内容");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "read_pdf",
                Map.of("eventType", "TOOL_START", "toolName", "read_pdf")
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                """
                根据 PDF 前 3 页内容，2026 年度湖南省重点研发计划项目申报指南的主要内容如下：
                - 人工智能是首要支持领域
                - 包括新一代人工智能与人工智能+两个大方向
                - 多个专题要求企业牵头申报

                需要我继续查看其他领域或更多详细内容吗？
                """,
                null
        );

        assertTrue(result.success());
    }

    @Test
    void shouldRejectDocumentTaskThatOnlyPromisesMoreWork() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-2", "总结这个 word 文件内容");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "read_word",
                Map.of("eventType", "TOOL_START", "toolName", "read_word")
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                "我先帮你读取这个 Word 文件，然后继续整理出要点给你。",
                null
        );

        assertFalse(result.success());
        assertTrue("DOCUMENT_SUMMARY_INCOMPLETE".equals(result.failureType()));
    }
}

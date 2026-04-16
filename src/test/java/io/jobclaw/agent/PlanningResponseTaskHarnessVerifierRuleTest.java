package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningResponseTaskHarnessVerifierRuleTest {

    private final PlanningResponseTaskHarnessVerifierRule rule = new PlanningResponseTaskHarnessVerifierRule();

    @Test
    void shouldRejectPlanningOnlyReply() {
        TaskHarnessVerificationResult result = rule.verify(
                new TaskHarnessRun("session-a", "run-1", "分析excel并给出结论"),
                "我准备用 Excel 分析技能先读取文件，然后再给你总结。",
                null
        );

        assertFalse(result.success());
        assertTrue("INCOMPLETE_RESPONSE".equals(result.failureType()));
    }

    @Test
    void shouldAcceptCompletedAnalysisReply() {
        TaskHarnessVerificationResult result = rule.verify(
                new TaskHarnessRun("session-a", "run-2", "分析excel并给出结论"),
                "分析结果：销售额在 3 月达到峰值，华东区贡献最高，结论是促销主要拉动了订单量。",
                null
        );

        assertTrue(result.success());
    }

    @Test
    void compositeVerifierShouldShortCircuitPlanningOnlyReplyBeforeDefaultAcceptance() {
        CompositeTaskHarnessVerifier verifier = new CompositeTaskHarnessVerifier(java.util.List.of(
                new PlanningResponseTaskHarnessVerifierRule(),
                new DefaultTaskHarnessVerifier()
        ));

        TaskHarnessVerificationResult result = verifier.verify(
                new TaskHarnessRun("session-a", "run-3", "分析excel并给出结论"),
                "接下来我会先分析表格，再把结果告诉你。",
                null
        );

        assertFalse(result.success());
    }

    @Test
    void shouldRejectResponseThatEndsWithFutureWorkEvenAfterSomeProgressText() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-4", "分析 excel 并给出结论");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "read_excel",
                java.util.Map.of("eventType", "TOOL_START", "toolName", "read_excel")
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                "我已经读取了表格内容，接下来我会继续分析异常值并整理最终结论。",
                null
        );

        assertFalse(result.success());
    }

    @Test
    void shouldAllowCompletedResponseWithOptionalConditionalFollowUp() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-5", "分析 excel 并给出结论");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "read_excel",
                java.util.Map.of("eventType", "TOOL_START", "toolName", "read_excel")
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                "分析结果：库存周转最慢的是华北仓。结论是补货策略偏保守。如果你需要，我可以继续拆分到 SKU 维度。",
                null
        );

        assertTrue(result.success());
    }

    @Test
    void shouldAllowCompletedResponseThatEndsWithNeedMeToContinueQuestion() {
        TaskHarnessRun run = new TaskHarnessRun("session-a", "run-6", "总结 pdf 前三页");
        run.addStep(
                TaskHarnessPhase.ACT,
                "event",
                "tool_start",
                "read_pdf",
                java.util.Map.of("eventType", "TOOL_START", "toolName", "read_pdf")
        );

        TaskHarnessVerificationResult result = rule.verify(
                run,
                """
                根据 PDF 前 3 页内容，2026 年度湖南省重点研发计划项目申报指南的主要内容如下：
                人工智能是首要支持领域。
                需要我继续查看其他领域或更多详细内容吗？
                """,
                null
        );

        assertTrue(result.success());
    }
}

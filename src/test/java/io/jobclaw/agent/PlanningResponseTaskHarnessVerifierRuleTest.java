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
        assertTrue(result.reason().contains("completed result"));
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
}

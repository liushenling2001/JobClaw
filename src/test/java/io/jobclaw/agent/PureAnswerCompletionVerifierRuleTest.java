package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureAnswerCompletionVerifierRuleTest {

    private final PureAnswerCompletionVerifierRule rule = new PureAnswerCompletionVerifierRule();

    @Test
    void shouldAcceptSubstantiveAnswerWithoutTools() {
        TaskHarnessVerificationResult result = rule.verify(
                new TaskHarnessRun("session-a", "run-1", "为什么 Java 要用接口？"),
                "因为接口可以解耦实现与调用方，便于扩展、测试和替换依赖，所以在复杂系统里更稳定。",
                null
        );

        assertTrue(result.success());
    }

    @Test
    void shouldRejectPlanningOnlyAnswerWithoutTools() {
        TaskHarnessVerificationResult result = rule.verify(
                new TaskHarnessRun("session-a", "run-2", "解释一下 JVM 内存模型"),
                "我先整理一下思路，接下来我会分几个部分说明。",
                null
        );

        assertFalse(result.success());
        assertTrue("PURE_ANSWER_INCOMPLETE".equals(result.failureType()));
    }
}

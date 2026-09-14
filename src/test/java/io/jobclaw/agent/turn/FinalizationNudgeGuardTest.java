package io.jobclaw.agent.turn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class FinalizationNudgeGuardTest {
    @Test
    void mergesBothReasonsAndSharesOneAllowanceInEitherOrder() {
        var completion = io.jobclaw.agent.completion.CompletionGateResult.unregistered();
        FinalizationNudgeGuard combined = new FinalizationNudgeGuard();
        String prompt = combined.continuationPrompt(completion, "ARTIFACT_CHECK", "让我先检查目录。", false, false);
        assertTrue(prompt.contains("ARTIFACT_CHECK"));
        assertTrue(prompt.contains("JOBCLAW_PROMISE_CONTINUATION"));
        assertEquals("", combined.continuationPrompt(completion, "ARTIFACT_CHECK", "让我先检查目录。", false, false));

        FinalizationNudgeGuard artifactFirst = new FinalizationNudgeGuard();
        assertFalse(artifactFirst.continuationPrompt(completion, "ARTIFACT_CHECK", "已经生成。", false, false).isEmpty());
        assertEquals("", artifactFirst.continuationPrompt(completion, "", "让我先检查目录。", false, false));

        FinalizationNudgeGuard promiseFirst = new FinalizationNudgeGuard();
        assertFalse(promiseFirst.continuationPrompt(completion, "", "让我先检查目录。", false, false).isEmpty());
        assertEquals("", promiseFirst.continuationPrompt(completion, "ARTIFACT_CHECK", "已经生成。", false, false));
    }

    @Test
    void registeredChecksNeverUseHeuristicRecovery() {
        for (boolean passed : new boolean[]{true, false}) {
            var completion = new io.jobclaw.agent.completion.CompletionGateResult(
                    true, passed, !passed, 0, 2, java.util.List.of(), "");
            var guard = new FinalizationNudgeGuard();
            assertEquals("", guard.continuationPrompt(completion, "ARTIFACT_CHECK", "让我先检查目录。", false, false));
            assertFalse(guard.hasIssued());
        }
    }

    @Test
    void waitingOrRunningWorkSuppressesBothHeuristicReasons() {
        var guard = new FinalizationNudgeGuard();
        var completion = io.jobclaw.agent.completion.CompletionGateResult.unregistered();
        assertEquals("", guard.continuationPrompt(completion, "ARTIFACT_CHECK", "让我先检查目录。", true, false));
        assertEquals("", guard.continuationPrompt(completion, "ARTIFACT_CHECK", "让我先检查目录。", false, true));
        assertFalse(guard.hasIssued());
    }

    @ParameterizedTest
    @ValueSource(strings = {"让我先检查目录。", "我现在开始整理。", "好的，我先读取文件。",
            "下一步我要检查文件夹。", "接下来我会整理文件。", "我现在运行测试。"})
    void matchesShortUnexecutedPromises(String text) {
        assertFalse(new FinalizationNudgeGuard().continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", text, false, false).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"你好！有什么需要帮忙的吗？", "文件已整理完成。", "以上就是全部技能。",
            "需要我整理吗？", "让我先检查目录，可以吗？", "请确认后我再整理。",
            "我会在你确认后整理文件。", "我先检查目录，等待你确认。",
            "我先检查目录。如果有重名，需要你确认。", "你可以先检查目录。",
            "```text\n让我先检查目录。\n```", "> 我先检查目录。", "“让我先检查目录。”",
            "这句话的意思是：让我先检查目录。", "我先检查目录。现在已整理完成。",
            "下一步建议检查目录。", "我会整理文件，这是一个示例。", ""})
    void leavesAnswersQuestionsQuotesAndCompletedWorkAlone(String text) {
        assertEquals("", new FinalizationNudgeGuard().continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", text, false, false));
    }

    @Test
    void onlyNudgesOnceEvenAfterNewToolProgress() {
        FinalizationNudgeGuard guard = new FinalizationNudgeGuard();
        assertFalse(guard.continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", "让我先检查目录。", false, false).isEmpty());
        assertEquals("", guard.continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", "我接下来会读取文件。", false, false));
    }

    @Test
    void pendingInputAndRunningWorkNeverGetNudged() {
        FinalizationNudgeGuard guard = new FinalizationNudgeGuard();
        assertEquals("", guard.continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", "让我先检查目录。", true, false));
        assertEquals("", guard.continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", "让我先检查目录。", false, true));
        assertFalse(guard.continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", "让我先检查目录。", false, false).isEmpty());
    }

    @Test
    void onlyChecksTextAfterLastToolBoundaryAndBoundsMemory() {
        TurnExecutionLedger ledger = new TurnExecutionLedger("session", "run");
        ledger.recordAssistantContent("让我先检查目录。", 0);
        ledger.syncToolRevision(1);
        assertEquals("", ledger.lastAssistantSegment());
        ledger.recordAssistantContent("目录已检查。", 2);
        assertFalse(FinalizationNudgeGuard.matches(ledger.lastAssistantSegment()));
        ledger.recordAssistantContent("x".repeat(1000), 2);
        assertEquals("", ledger.lastAssistantSegment());
        ledger.recordAssistantContent("让我先读取文件。", 2);
        assertEquals("", ledger.lastAssistantSegment(), "Never match a truncated suffix of a long answer");
        ledger.recordAssistantContent("让我先", 4);
        ledger.recordAssistantContent("读取文件。", 4);
        assertTrue(FinalizationNudgeGuard.matches(ledger.lastAssistantSegment()));
        ledger.beginModelAttempt();
        assertEquals("", ledger.lastAssistantSegment());
    }
}

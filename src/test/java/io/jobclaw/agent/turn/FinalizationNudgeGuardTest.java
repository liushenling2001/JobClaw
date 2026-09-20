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
            "下一步我要检查文件夹。", "接下来我会整理文件。", "我现在运行测试。",
            "继续查看下一个目录", "继续查看下一个目录。", "我继续读取下一个文件。", "我先继续检查目录。"})
    void matchesShortUnexecutedPromises(String text) {
        assertFalse(new FinalizationNudgeGuard().continuationPrompt(io.jobclaw.agent.completion.CompletionGateResult.unregistered(), "", text, false, false).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"接下来查看下一个目录。", "下一步，读取第二个文件。", "接着检查剩余目录。",
            "现在开始处理剩余文件。", "随后核对文件清单。", "然后继续扫描子目录。",
            "接下来，我先检索相关文档。", "下一步我将导出表格。", "现在我来汇总结果。",
            "我再查看一个目录。", "我来检查文件内容。", "我马上读取配置。", "我立刻运行测试。",
            "我准备整理文件。", "我打算提取正文。", "让我查看一下目录。", "让我再检查一次。",
            "我会继续读取剩余文件。", "我接着处理下一批。", "我将开始转换文档。",
            "继续核对清单。", "接下来统计文件数量。", "接着校验输出文件。", "我先对比两个版本。",
            "下一步遍历子目录。", "我先解压文件。", "接下来合并结果。", "我先复制文件到目标目录。"})
    void matchesAdditionalActionAnnouncements(String text) {
        assertTrue(FinalizationNudgeGuard.matches(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"下一步是什么？", "接下来查看下一个目录吗", "接着检查剩余目录是否合适？",
            "接下来请你查看目录。", "下一步建议读取文件。", "现在可以开始处理文件。",
            "我来解释一下这个概念。", "我会整理文件的方法有三种。", "接下来查看目录的步骤如下：",
            "下一步读取配置，这是操作说明。", "接下来查看目录即可。", "我准备整理文件，但需要您提供路径。",
            "我会读取文件，等你上传。", "我来检查目录，但没有权限。", "我再读取文件，但暂时不可用。",
            "我先检查了目录。", "我先读取文件，已经完成。", "接下来处理文件的工作已结束。",
            "我再检查目录，暂时不执行。", "接下来不需要处理文件。", "我会继续读取文件，但需要用户确认。",
            "接着检查文件，结果如下。", "现在开始处理文件是错误的说法。", "随后核对清单，这只是计划。",
            "我先检查目录；然后需要你确认。", "下一步：读取配置。\n第二步：修改参数。",
            "我来检查 `config.json`。", "我先看看。", "继续。", "先检查目录。"})
    void excludesAdvicePastActionsBlockersAndAmbiguousReplies(String text) {
        assertFalse(FinalizationNudgeGuard.matches(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"你好！有什么需要帮忙的吗？", "文件已整理完成。", "以上就是全部技能。",
            "需要我整理吗？", "让我先检查目录，可以吗？", "请确认后我再整理。",
            "我会在你确认后整理文件。", "我先检查目录，等待你确认。",
            "我先检查目录。如果有重名，需要你确认。", "你可以先检查目录。",
            "```text\n让我先检查目录。\n```", "> 我先检查目录。", "“让我先检查目录。”",
            "这句话的意思是：让我先检查目录。", "我先检查目录。现在已整理完成。",
            "下一步建议检查目录。", "我会整理文件，这是一个示例。",
            "继续查看下一个目录吗？", "请继续查看下一个目录。", "你可以继续查看下一个目录。",
            "继续查看下一个目录，需要你确认。", "继续查看下一个目录是一个示例。",
            "“继续查看下一个目录”", "继续查看下一个目录，已完成。", ""})
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

package io.jobclaw.agent;

import io.jobclaw.config.AgentConfig;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.FileResultStore;
import io.jobclaw.runtime.tool.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTrajectoryCompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldScaleTriggerWithModelContextInsteadOfUsingAnAbsoluteCap() {
        AgentConfig config = new AgentConfig();
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config);

        assertEquals(80_000, compactor.triggerTokens(100_000, 16_384));
        assertEquals(209_715, compactor.triggerTokens(262_144, 32_768));
        assertEquals(800_000, compactor.triggerTokens(1_000_000, 32_768));
        assertEquals(16_000, compactor.retainTokens(100_000));
        assertEquals(41_943, compactor.retainTokens(262_144));
    }

    @Test
    void shouldCountOnlyTheContextReferenceEnvelopeInsteadOfItsStoredContentLength() {
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(new AgentConfig());
        ContextRef ref = new ContextRef(
                "ref-large-result",
                "session-ref",
                "run-ref",
                "tool",
                "read_pdf",
                Instant.now(),
                2_000_000,
                "preview ".repeat(250)
        );

        String modelResponse = ToolRuntime.toContextReferenceResponse(ref);

        assertTrue(modelResponse.contains("contentLength: 2000000"));
        assertTrue(compactor.estimateTextTokens(modelResponse) < 1_000);
    }

    @Test
    void shouldCountOnlyTheContextReferenceSliceThatActuallyFlowsBackToTheModel() {
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(new AgentConfig());
        String returnedSlice = "Context reference: ref-large-result\n"
                + "Source: read_pdf\n"
                + "Range: 12000-24000 of 2000000\n\n"
                + "文".repeat(12_000);

        int estimatedTokens = compactor.estimateTextTokens(returnedSlice);

        assertTrue(estimatedTokens >= 12_000);
        assertTrue(estimatedTokens < 12_100);
    }

    @Test
    void shouldReplaceOldRunTrajectoryWithStateCard() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(8_000);
        FileResultStore resultStore = new FileResultStore(tempDir);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config, resultStore);
        String currentTask = "处理当前文件夹并生成报告";
        String oldLargeText = "old verbose response ".repeat(2_000)
                + "refId: ref-abc123\nmanifestId: mf-001\nD:\\work\\report.xlsx\nError: previous failure";
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage(currentTask));
        messages.add(new AssistantMessage(oldLargeText));
        messages.add(new UserMessage("old repair prompt " + "x".repeat(10_000)));
        messages.add(new AssistantMessage("old retry answer " + "y".repeat(8_000)));
        messages.add(new UserMessage("old gate prompt " + "z".repeat(8_000)));
        messages.add(new AssistantMessage("recent assistant"));
        messages.add(new UserMessage("recent guard"));

        compactor.compactIfNeeded(messages, "session-1", "run-1", currentTask);

        String joined = messages.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("JOBCLAW_RUN_TRAJECTORY_SUMMARY"));
        assertTrue(joined.contains("ref-abc123"));
        assertTrue(joined.contains("mf-001"));
        assertTrue(joined.contains("D:\\work\\report.xlsx"));
        assertTrue(joined.contains(currentTask));
        assertTrue(joined.contains("recent assistant"));
        assertTrue(joined.contains("recent guard"));
        assertTrue(joined.length() < oldLargeText.length() / 3);
        assertTrue(countOccurrences(joined, "old verbose response") < 40);

        List<ContextRef> archives = resultStore.list("session-1", "run-1", 10);
        assertEquals(1, archives.size());
        assertEquals("run-trajectory", archives.get(0).getSourceType());
        assertTrue(joined.contains(archives.get(0).getRefId()));
        assertTrue(resultStore.find(archives.get(0).getRefId()).orElseThrow().getContent()
                .contains("old verbose response"));
    }

    @Test
    void shouldUseTokenEstimateForChineseTrajectory() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(8_000);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config, new FileResultStore(tempDir));
        String currentTask = "检查文档并继续完成当前任务";
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage(currentTask));
        messages.add(new AssistantMessage("已读取第一页。".repeat(1_200)));
        messages.add(new UserMessage("继续检查，不要重新读取第一页。"));
        messages.add(new AssistantMessage("已读取第二页。".repeat(1_200)));
        messages.add(new UserMessage("继续"));
        messages.add(new AssistantMessage("当前进度：已完成前两页。"));
        messages.add(new UserMessage("检查第三页"));

        compactor.compactIfNeeded(messages, "session-cn", "run-cn", currentTask);

        String joined = joined(messages);
        assertTrue(joined.contains("JOBCLAW_RUN_TRAJECTORY_SUMMARY"));
        assertTrue(joined.contains(currentTask));
        assertTrue(joined.contains("检查第三页"));
        assertTrue(joined.contains("不要重新读取第一页"));
        assertTrue(countOccurrences(joined, "已读取第一页。") < 80);
    }

    @Test
    void shouldKeepToolCallAndToolResponseInTheSameMessageGroup() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(8_000);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config, new FileResultStore(tempDir));
        String currentTask = "读取文件后生成报告";
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "read_file",
                        "{\"path\":\"D:\\\\work\\\\input.txt\"}"
                )))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "read_file",
                        "file content"
                )))
                .build();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage(currentTask));
        messages.add(new AssistantMessage("old response ".repeat(6_000)));
        messages.add(new UserMessage("old continuation"));
        messages.add(new AssistantMessage("another old response"));
        messages.add(toolCall);
        messages.add(toolResponse);
        messages.add(new UserMessage("根据刚才读取结果继续"));

        compactor.compactIfNeeded(messages, "session-tools", "run-tools", currentTask);

        int toolCallIndex = messages.indexOf(toolCall);
        assertTrue(toolCallIndex >= 0);
        assertTrue(toolCallIndex + 1 < messages.size());
        assertInstanceOf(ToolResponseMessage.class, messages.get(toolCallIndex + 1));
    }

    @Test
    void shouldCountAndArchiveLargeToolResponses() {
        AgentConfig config = new AgentConfig();
        config.setCompactionTriggerPercentage(80);
        config.setCompactionRetainPercentage(16);
        FileResultStore resultStore = new FileResultStore(tempDir);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config, resultStore);
        String currentTask = "读取数据并完成分析";
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-large", "function", "read_file", "{\"path\":\"large.txt\"}")))
                .build();
        String largeResult = "large-tool-payload ".repeat(5_000);
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-large", "read_file", largeResult)))
                .build();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage(currentTask));
        messages.add(toolCall);
        messages.add(toolResponse);
        messages.add(new AssistantMessage("工具读取已经完成"));
        messages.add(new UserMessage("根据读取结果继续"));

        compactor.compactIfNeeded(messages, "session-large-tool", "run-large-tool",
                currentTask, 20_000, 2_000);

        assertTrue(joined(messages).contains("JOBCLAW_RUN_TRAJECTORY_SUMMARY"));
        List<ContextRef> archives = resultStore.list("session-large-tool", "run-large-tool", 10);
        assertEquals(1, archives.size());
        String archived = resultStore.find(archives.get(0).getRefId()).orElseThrow().getContent();
        assertTrue(archived.contains("call-large"));
        assertTrue(archived.contains("large-tool-payload"));
    }

    @Test
    void shouldReplacePreviousStateCardInsteadOfAccumulatingSummaries() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(8_000);
        FileResultStore resultStore = new FileResultStore(tempDir);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config, resultStore);
        String currentTask = "完成长任务";
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage(currentTask));
        messages.add(new AssistantMessage("first pass ".repeat(6_000)));
        messages.add(new UserMessage("first guard"));
        messages.add(new AssistantMessage("first retry"));
        messages.add(new UserMessage("second guard"));
        messages.add(new AssistantMessage("current checkpoint"));
        messages.add(new UserMessage("continue"));

        compactor.compactIfNeeded(messages, "session-repeat", "run-repeat", currentTask);
        messages.add(new AssistantMessage("second pass ".repeat(6_000)));
        messages.add(new UserMessage("third guard"));
        messages.add(new AssistantMessage("second checkpoint"));
        messages.add(new UserMessage("finish pending work"));
        compactor.compactIfNeeded(messages, "session-repeat", "run-repeat", currentTask);

        long summaryCount = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .filter(text -> text.contains("JOBCLAW_RUN_TRAJECTORY_SUMMARY"))
                .count();
        assertEquals(1, summaryCount);
        assertEquals(2, resultStore.list("session-repeat", "run-repeat", 10).size());
        assertTrue(joined(messages).contains("finish pending work"));
        assertTrue(joined(messages).contains(currentTask));
    }

    private String joined(List<Message> messages) {
        return messages.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
    }

    private int countOccurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}

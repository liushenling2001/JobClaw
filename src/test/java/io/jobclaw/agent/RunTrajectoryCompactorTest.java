package io.jobclaw.agent;

import io.jobclaw.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTrajectoryCompactorTest {

    @Test
    void shouldReplaceOldRunTrajectoryWithStateCard() {
        AgentConfig config = new AgentConfig();
        config.setContextWindow(8_000);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config);
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
        assertFalse(joined.contains("old verbose response old verbose response old verbose response"));
    }
}

package io.jobclaw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.config.AgentConfig;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.FileResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTrajectoryCompactorReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldReplayLongRunCompactionScenarios() throws Exception {
        List<CompactionReplayScenario> scenarios;
        try (InputStream input = resource("harness/replays/agent/context-compaction.json")) {
            scenarios = MAPPER.readValue(input, new TypeReference<>() {
            });
        }

        for (CompactionReplayScenario scenario : scenarios) {
            AgentConfig config = new AgentConfig();
            config.setContextWindow(scenario.contextWindow());
            FileResultStore resultStore = new FileResultStore(tempDir.resolve(scenario.id()));
            RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config, resultStore);
            List<Message> messages = toMessages(scenario.messages());
            int beforeChars = joined(messages).length();

            compactor.compactIfNeeded(messages, "replay-session", scenario.id(), scenario.currentTask());

            String compacted = joined(messages);
            for (String expected : scenario.expectedContains()) {
                assertTrue(compacted.contains(expected), scenario.id() + " missing compacted state: " + expected);
            }
            assertTrue(compacted.length() < beforeChars / 3, scenario.id() + " did not compact enough");

            List<ContextRef> archives = resultStore.list("replay-session", scenario.id(), 10);
            assertEquals(1, archives.size(), scenario.id());
            String archive = resultStore.find(archives.get(0).getRefId()).orElseThrow().getContent();
            for (String expected : scenario.expectedArchiveContains()) {
                assertTrue(archive.contains(expected), scenario.id() + " missing archived state: " + expected);
            }
        }
    }

    private List<Message> toMessages(List<MessageSpec> specs) {
        List<Message> messages = new ArrayList<>();
        for (MessageSpec spec : specs) {
            String content = spec.content().repeat(Math.max(1, spec.repeat()));
            messages.add(switch (spec.role()) {
                case "system" -> new SystemMessage(content);
                case "assistant" -> new AssistantMessage(content);
                default -> new UserMessage(content);
            });
        }
        return messages;
    }

    private String joined(List<Message> messages) {
        return messages.stream().map(Message::getText).reduce("", (left, right) -> left + "\n" + right);
    }

    private InputStream resource(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        if (input == null) {
            throw new IllegalArgumentException("Missing replay resource: " + path);
        }
        return input;
    }

    private record CompactionReplayScenario(
            String id,
            int contextWindow,
            String currentTask,
            List<MessageSpec> messages,
            List<String> expectedContains,
            List<String> expectedArchiveContains
    ) {
    }

    private record MessageSpec(String role, String content, int repeat) {
    }
}

package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.config.ModelsConfig;
import io.jobclaw.context.result.FileResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagingToolCallingAdvisorTest {

    @TempDir
    Path tempDir;

    @Test
    void compactsConversationBeforeAnInternalToolLoopRequestUsingModelCapacity() {
        Config config = Config.defaultConfig();
        config.getAgent().setModel("tool-model");
        ModelsConfig.ModelDefinition model = new ModelsConfig.ModelDefinition("openai", "tool-model", 20_000);
        model.setMaxTokens(8_000);
        config.getModels().getDefinitions().put("tool-model", model);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(
                config.getAgent(), new FileResultStore(tempDir));
        ToolCallingAdvisor advisor = ContextManagingToolCallingAdvisor.builder(config, compactor)
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("完成当前长任务"));
        for (int i = 0; i < 8; i++) {
            messages.add(new AssistantMessage(("old tool round " + i + " ").repeat(1_000)));
            messages.add(new UserMessage("continue round " + i));
        }
        messages.add(new AssistantMessage("latest checkpoint"));
        messages.add(new UserMessage("继续完成当前长任务"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(messages, OpenAiChatOptions.builder().model("tool-model").build()))
                .context(Map.of(
                        ContextManagingToolCallingAdvisor.SESSION_ID_CONTEXT_KEY, "session-1",
                        ContextManagingToolCallingAdvisor.RUN_ID_CONTEXT_KEY, "run-1"))
                .build();

        ChatClientRequest compacted = ((ContextManagingToolCallingAdvisor) advisor).compactRequest(request);
        String joined = compacted.prompt().getInstructions().stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(compacted.prompt().getInstructions().size() < messages.size());
        assertTrue(joined.contains("JOBCLAW_RUN_TRAJECTORY_SUMMARY"));
        assertTrue(joined.contains("latest checkpoint"));
        assertEquals(1, new FileResultStore(tempDir).list("session-1", "run-1", 10).size());
    }

    @Test
    void includesToolDefinitionsInRequestPressure() {
        Config config = Config.defaultConfig();
        config.getAgent().setModel("schema-model");
        ModelsConfig.ModelDefinition model = new ModelsConfig.ModelDefinition("openai", "schema-model", 20_000);
        model.setMaxTokens(2_000);
        config.getModels().getDefinitions().put("schema-model", model);
        FileResultStore resultStore = new FileResultStore(tempDir);
        RunTrajectoryCompactor compactor = new RunTrajectoryCompactor(config.getAgent(), resultStore);
        ToolCallback largeSchemaTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("large_schema")
                        .description("large schema test")
                        .inputSchema("x".repeat(52_000))
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
        ToolCallingAdvisor advisor = ContextManagingToolCallingAdvisor.builder(config, compactor)
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("完成任务"));
        for (int i = 0; i < 12; i++) {
            messages.add(new AssistantMessage(("old context " + i + " ").repeat(120)));
            messages.add(new UserMessage("continue " + i));
        }
        messages.add(new UserMessage("继续完成任务"));
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("schema-model")
                .toolCallbacks(largeSchemaTool)
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(messages, options))
                .context(Map.of())
                .build();

        ChatClientRequest compacted = ((ContextManagingToolCallingAdvisor) advisor).compactRequest(request);

        assertTrue(compacted.prompt().getInstructions().size() < messages.size());
        assertEquals(1, resultStore.list("tool-loop", "tool-loop", 10).size());
    }
}

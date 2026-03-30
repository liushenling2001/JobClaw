package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopSystemPromptOverlayTest {

    @Test
    void shouldReuseBaseSystemPromptAndAppendAgentOverlay() throws Exception {
        Path workspace = Files.createTempDirectory("agent-loop-overlay");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());

        String conversationPath = workspace.resolve("sessions").resolve("conversation").toString();
        SummaryService summaryService = new FileSummaryService(conversationPath);
        SessionManager sessionManager = new SessionManager(
                workspace.resolve("sessions").toString(),
                new FileConversationStore(conversationPath),
                summaryService
        );
        ContextBuilder contextBuilder = new ContextBuilder(config, sessionManager, null, summaryService);
        AgentLoop agentLoop = new AgentLoop(
                config,
                sessionManager,
                new ToolCallback[0],
                contextBuilder,
                (sessionKey, currentMessage, options) -> java.util.List.of(),
                (sessionKey, userContent) -> new io.jobclaw.context.ContextAssemblyOptions(0, 0, 0, 0, 0),
                summaryService
        );

        AgentDefinition definition = AgentDefinition.builder()
                .code("jd_analyst")
                .displayName("JD Analyst")
                .description("Analyze job descriptions")
                .systemPrompt("Focus on extracting structured requirements.")
                .allowedTool("read_file")
                .build();

        Method method = AgentLoop.class.getDeclaredMethod(
                "buildSystemPromptWithDefinition",
                String.class,
                String.class,
                AgentDefinition.class
        );
        method.setAccessible(true);
        String prompt = (String) method.invoke(agentLoop, "web:test", "analyze this jd", definition);

        assertTrue(prompt.contains("# JobClaw"));
        assertTrue(prompt.contains("agent_catalog"));
        assertTrue(prompt.contains("# Agent Overlay"));
        assertTrue(prompt.contains("JD Analyst"));
        assertTrue(prompt.contains("Focus on extracting structured requirements."));
        assertTrue(prompt.contains("No agent-specific tool override is set") || prompt.contains("You are only allowed to use: read_file"));
    }
}

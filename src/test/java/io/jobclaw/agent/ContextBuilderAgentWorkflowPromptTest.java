package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBuilderAgentWorkflowPromptTest {

    @Test
    void shouldMentionPersistentAgentWorkflowInSystemPrompt() throws Exception {
        Path workspace = Files.createTempDirectory("context-builder-agent-workflow");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());

        String sessionsPath = workspace.resolve("sessions").toString();
        SummaryService summaryService = new FileSummaryService(workspace.resolve("sessions").resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(
                sessionsPath,
                new FileConversationStore(workspace.resolve("sessions").resolve("conversation").toString()),
                summaryService
        );
        ContextBuilder builder = new ContextBuilder(config, sessionManager, null, summaryService, new MCPService());

        String prompt = builder.buildSystemPrompt("web:test", "帮我创建一个专门做JD分析的智能体");

        assertTrue(prompt.contains("agent_catalog"));
        assertTrue(prompt.contains("spawn(agent='saved-agent-name'"));
        assertTrue(prompt.contains("spawn(role='coder'"));
    }
}

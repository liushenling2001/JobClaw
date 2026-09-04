package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.conversation.file.FileConversationStore;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.summary.file.FileSummaryService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void shouldInstructModelToRegisterCompletionForExplicitArtifacts() throws Exception {
        Path workspace = Files.createTempDirectory("context-builder-completion-artifact");
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

        String prompt = builder.buildSystemPrompt("web:test", "生成一个excel放到当前目录");

        assertTrue(prompt.contains("completion(action='register'"));
        assertTrue(prompt.contains("artifact_expected"));
        assertTrue(prompt.contains("final response must include the full generated artifact path"));
        assertTrue(prompt.contains("explicit artifact requirement"));
        assertTrue(prompt.contains("Registered completion checks are final-response guards only"));
    }

    @Test
    void shouldLeaveConversationSummaryInjectionToContextAssembler() throws Exception {
        Path workspace = Files.createTempDirectory("context-builder-stateful-summary");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());

        String sessionsPath = workspace.resolve("sessions").toString();
        SummaryService summaryService = new FileSummaryService(workspace.resolve("sessions").resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(
                sessionsPath,
                new FileConversationStore(workspace.resolve("sessions").resolve("conversation").toString()),
                summaryService
        );
        String sessionKey = "web:stateful";
        summaryService.saveSessionSummary(new SessionSummaryRecord(
                sessionKey,
                "旧任务 inputDir=D:\\old\\docs manifestId=old-mf artifactPath=D:\\old\\result.xlsx pending=0 done=20",
                List.of(),
                List.of(),
                List.of(),
                1,
                1,
                Instant.now()
        ));
        ContextBuilder builder = new ContextBuilder(config, sessionManager, null, summaryService, new MCPService());

        String prompt = builder.buildSystemPrompt(sessionKey, "处理 D:\\new\\docs");

        assertFalse(prompt.contains("# Conversation Summary"));
        assertFalse(prompt.contains("old-mf"));
        assertTrue(prompt.contains("inputDir, fields, outputDir, outputPath, and manifestId must come from the current user message"));
    }

    @Test
    void shouldNotInjectHistoricalManifestIdsUnlessContinuingPriorTask() throws Exception {
        Path workspace = Files.createTempDirectory("context-builder-manifest-isolation");
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(workspace.toString());

        String sessionsPath = workspace.resolve("sessions").toString();
        SummaryService summaryService = new FileSummaryService(workspace.resolve("sessions").resolve("conversation").toString());
        SessionManager sessionManager = new SessionManager(
                sessionsPath,
                new FileConversationStore(workspace.resolve("sessions").resolve("conversation").toString()),
                summaryService
        );
        String sessionKey = "web:manifest";
        Path manifestDir = workspace.resolve(".jobclaw").resolve("manifests").resolve("web_manifest");
        Files.createDirectories(manifestDir);
        Files.writeString(manifestDir.resolve("old-mf.json"), """
                {
                  "manifestId": "old-mf",
                  "taskKey": "old task",
                  "artifactPath": "D:\\\\old\\\\result.xlsx",
                  "items": {
                    "a": {"status": "done"}
                  }
                }
                """);
        ContextBuilder builder = new ContextBuilder(config, sessionManager, null, summaryService, new MCPService());

        String prompt = builder.buildSystemPrompt(sessionKey, "处理 D:\\new\\docs");

        assertTrue(prompt.contains("Existing manifests from prior turns are not injected"));
        assertTrue(!prompt.contains("old-mf"));
        assertTrue(!prompt.contains("D:\\old\\result.xlsx"));
    }
}

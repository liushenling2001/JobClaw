package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.config.MCPServersConfig;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.providers.Message;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillInfo;
import io.jobclaw.skills.SkillSelectionPolicy;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Builds stable system-prompt context.
 *
 * Current responsibilities:
 * - identity/runtime/workspace information
 * - bootstrap files such as AGENTS.md / SOUL.md
 * - installed skills summary
 * - long-term memory context
 * - session summary
 *
 * Historical message assembly has moved to ContextAssembler.
 */
@Component
public class ContextBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ContextBuilder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SECTION_SEPARATOR = "\n\n---\n\n";

    private static final String[] BOOTSTRAP_FILES = {
            "AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md"
    };
    private static final Pattern HISTORICAL_EXECUTION_STATE_PATTERN = Pattern.compile(
            "(?i)(manifestId|artifactPath|context_ref|refId|pending\\s*=|running\\s*=|done\\s*=|failed\\s*=|inputDir|outputDir|output_path|intermediate_path|\\.jsonl|\\.xlsx|\\.pdf|[A-Z]:\\\\)"
    );

    private final Config config;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final SkillsService skillsService;
    private final SkillSelectionPolicy skillSelectionPolicy;
    private final SummaryService summaryService;
    private final MCPService mcpService;
    private final Map<String, String> fileContentCache;
    private final String workspace;

    private int contextWindow;

    public ContextBuilder(Config config,
                          SessionManager sessionManager,
                          SkillsService skillsService,
                          SummaryService summaryService,
                          MCPService mcpService) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.skillsService = skillsService;
        this.skillSelectionPolicy = new SkillSelectionPolicy();
        this.summaryService = summaryService;
        this.mcpService = mcpService;
        this.fileContentCache = new ConcurrentHashMap<>();
        this.workspace = ConfigLoader.expandHome(config.getAgent().getWorkspace());
        this.contextWindow = config.getAgent().getContextWindow();
        this.memoryStore = new MemoryStore(this.workspace);

        logger.info("ContextBuilder initialized with workspace: {}", this.workspace);
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public MemoryStore getMemoryStore() {
        return memoryStore;
    }

    public SkillsService getSkillsService() {
        return skillsService;
    }

    /**
     * Legacy compatibility helper. Only returns system + current user message.
     * History assembly now belongs to ContextAssembler.
     */
    public List<Message> buildMessages(String sessionKey, String userContent) {
        String systemPrompt = buildSystemPrompt(sessionKey, userContent);
        logger.debug("System prompt built for session: {}, total_chars: {}",
                sessionKey, systemPrompt.length());

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(userContent));
        return messages;
    }

    public String buildSystemPrompt(String sessionKey) {
        return buildSystemPrompt(sessionKey, null);
    }

    public String buildSystemPrompt(String sessionKey, String currentMessage) {
        List<String> parts = new ArrayList<>();

        parts.add(getIdentity());
        addSectionIfNotBlank(parts, loadBootstrapFiles());
        addSectionIfNotBlank(parts, buildSkillsSection(currentMessage));
        addSectionIfNotBlank(parts, buildMcpSection());

        int memoryBudget = calculateMemoryTokenBudget();
        String memoryContext = memoryStore.getMemoryContext(currentMessage, memoryBudget);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            parts.add("# Memory\n\n" + memoryContext);
        }

        String summary = resolveSessionSummary(sessionKey);
        if (summary != null && !summary.isBlank()) {
            parts.add("# Conversation Summary\n\n" + buildSafeConversationSummary(summary));
        }

        addSectionIfNotBlank(parts, buildManifestSection(sessionKey, currentMessage));
        parts.add(buildCurrentSessionInfo(sessionKey));
        return String.join(SECTION_SEPARATOR, parts);
    }

    private String resolveSessionSummary(String sessionKey) {
        if (summaryService != null) {
            return summaryService.getSessionSummary(sessionKey)
                    .map(SessionSummaryRecord::summaryText)
                    .filter(value -> !value.isBlank())
                    .orElse(sessionManager.getSummary(sessionKey));
        }
        return sessionManager.getSummary(sessionKey);
    }

    private int calculateMemoryTokenBudget() {
        int budget = contextWindow * config.getAgent().getMemoryTokenBudgetPercentage() / 100;
        return Math.max(config.getAgent().getMemoryMinTokenBudget(),
                Math.min(config.getAgent().getMemoryMaxTokenBudget(), budget));
    }

    private String buildSkillsSection(String currentMessage) {
        if (skillsService == null) {
            return "";
        }

        List<SkillInfo> selectedSkills = skillSelectionPolicy.selectInstalledSkills(
                skillsService.listSkills(),
                currentMessage,
                5
        );
        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");

        sb.append("To find installable skills, call the tool named `skills` with JSON arguments like {\"action\":\"search\",\"query\":\"...\"}. ");
        sb.append("To open an installed skill and get its base path, call `skills` with {\"action\":\"invoke\",\"name\":\"skill-name\"}.\n\n");

        if (!selectedSkills.isEmpty()) {
            sb.append("## Relevant Installed Skills\n\n");
            sb.append("<skills>\n");
            for (SkillInfo skill : selectedSkills) {
                sb.append("  <skill>\n");
                sb.append("    <name>").append(escapeXml(skill.getName())).append("</name>\n");
                sb.append("    <description>").append(escapeXml(skill.getDescription())).append("</description>\n");
                sb.append("    <source>").append(escapeXml(skill.getSource())).append("</source>\n");
                sb.append("  </skill>\n");
            }
            sb.append("</skills>\n\n");
        } else {
            sb.append("No installed skill was selected for this request. Search or invoke skills only if the task requires specialized reusable instructions.\n\n");
        }

        appendSkillSelfLearningGuide(sb);
        return sb.toString();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void appendSkillSelfLearningGuide(StringBuilder sb) {
        String skillsPath = Paths.get(workspace).toAbsolutePath() + "/skills/";

        sb.append("""
                ## Skill Management

                Prefer searching or invoking skills only when the task needs specialized reusable instructions.
                For script-backed skills, invoke the skill first to get its base path, then run scripts from that path.
                """);

        sb.append("\nSkills are stored in `").append(skillsPath).append("`.\n");
    }

    private String buildMcpSection() {
        if (mcpService == null) {
            return "";
        }

        MCPServersConfig mcpConfig = config.getMcpServers();
        if (mcpConfig == null || !mcpConfig.isEnabled()) {
            return "";
        }

        mcpService.ensureConfiguredServersConnected(mcpConfig);

        List<String> enabledServers = mcpConfig.getServers().stream()
                .filter(server -> server != null && server.isEnabled())
                .map(MCPServersConfig.MCPServerConfig::getName)
                .map(this::safeTrim)
                .filter(name -> !name.isEmpty())
                .toList();

        if (enabledServers.isEmpty()) {
            return "";
        }

        return "# MCP\n\n"
                + "Use `mcp` tool for external MCP servers.\n"
                + "Configured servers: " + String.join(", ", enabledServers) + ".";
    }

    private void addSectionIfNotBlank(List<String> parts, String section) {
        if (section != null && !section.trim().isEmpty()) {
            parts.add(section);
        }
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String getIdentity() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));
        String workspacePath = Paths.get(workspace).toAbsolutePath().toString();
        String runtime = System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", Java " + System.getProperty("java.version");

        StringBuilder sb = new StringBuilder();
        sb.append("# JobClaw\n\n");
        sb.append("You are JobClaw, a helpful AI assistant.\n\n");
        sb.append("## Current Time\n").append(now).append("\n\n");
        sb.append("## Runtime\n").append(runtime).append("\n\n");
        sb.append("## Workspace\n");
        sb.append("Workspace: ").append(workspacePath).append("\n");
        sb.append("- Memory: ").append(workspacePath).append("/memory/MEMORY.md\n");
        sb.append("- Daily notes: ").append(workspacePath).append("/memory/YYYYMM/YYYYMMDD.md\n\n");
        sb.append("## Rules\n\n");
        sb.append("1. Use tools when you need to perform actions.\n");
        sb.append("2. Be concise and accurate.\n");
        sb.append("3. Use workspace files for task inputs, outputs, reports, and configuration; do not treat ordinary files as durable user memory.\n");
        sb.append("4. When the user explicitly asks you to remember something, future behavior, preferences, or durable rules, call `memory(action='remember', content='...', scope='user|project|agent')`.\n");
        sb.append("5. Use `memory(action='search'|'list'|'forget')` to inspect or manage durable memory. Durable memory is stored in the structured memory system, not ad-hoc files.\n");
        sb.append("6. To create a reusable specialized agent, use `agent_catalog` to persist the definition.\n");
        sb.append("7. To run an existing persistent agent, use `spawn(agent='agent-name', task='...')`.\n");
        sb.append("8. Do not invent a parallel agent execution flow when `spawn` already fits the task.\n");
        sb.append("9. For batch work with independent items, use `manifest` only as an explicit ledger. If the user names a skill, first call the tool named `skills` with JSON arguments {\"action\":\"invoke\",\"name\":\"...\"} and follow that skill. Use `executionMode='managed'` only when the active skill declares a managed runner; in that mode the framework advances item state and writes intermediate item artifacts. Direct manifests are ledger-only: do not expect the framework to execute them. Use real item objects, not examples or placeholders, and do not keep the full item table in conversation.\n");
        sb.append("10. For file tools, copy paths exactly from `list_dir` output or user input. Never add, remove, split, translate, or reformat spaces and Chinese characters in file names.\n");
        sb.append("11. Large tool or sub-agent results may be returned as a `refId` instead of full text. Use `context_ref(action='read'|'search'|'summary', refId='...')` to inspect only the details needed for the task.\n");
        sb.append("12. If a recent tool result is already present or referenced by `refId`, reuse it instead of repeating the same read/search call. Repeat only when arguments change or a fresh read is explicitly needed.\n");
        sb.append("13. When the user or a skill explicitly asks you to create, save, export, or modify an artifact, register the completion requirement before final response. If the artifact type is known but the exact path is not yet stable, call `completion(action='register', checks='[{\"type\":\"artifact_expected\",\"artifactType\":\"file|directory|xlsx|pdf|jsonl|csv|docx|...\",\"outputDir\":\"...\"}]', onFail='if the artifact already exists, provide its full path in the final response; otherwise continue and create it')`. Use the most specific artifactType you know; unknown types are still valid but the final response must include one concrete absolute path. If the concrete path is already stable, you may register concrete `file_exists`/`file_non_empty` or `directory_exists`/`directory_non_empty` checks. Do not register completion for ordinary chat without an explicit artifact requirement.\n");
        sb.append("14. Registered completion checks are final-response guards only; they do not perform the task and do not interrupt normal execution. For `artifact_expected`, the final response must include the full generated artifact path so the framework can verify it; if verification fails, follow the returned recovery instruction and continue or report a blocked error, never claim success.\n");
        sb.append("15. For skill-driven work, task parameters such as inputDir, fields, outputDir, outputPath, and manifestId must come from the current user message or a tool result produced in the current run. Do not reuse historical paths, schemas, manifest ids, artifact paths, or pending/done state from summaries or similar prior tasks unless the user explicitly asks to continue that exact task.\n");
        sb.append("\n");
        sb.append("## Persistent Agents\n\n");
        sb.append("- Use `agent_catalog(action='create', ...)` to create a reusable agent.\n");
        sb.append("- Use `agent_catalog(action='list')` or `agent_catalog(action='get', name='...')` to inspect saved agents.\n");
        sb.append("- Use `spawn(agent='saved-agent-name', task='...')` to execute a saved agent.\n");
        sb.append("- Use `spawn(role='coder', task='...')` only for built-in role agents.\n");
        sb.append("- Spawned children are independent direct executions. Verify their final result before using it in the parent task.\n");
        return sb.toString();
    }

    private String loadBootstrapFiles() {
        StringBuilder result = new StringBuilder();

        for (String filename : BOOTSTRAP_FILES) {
            String content = loadBootstrapFile(filename);
            if (content != null && !content.trim().isEmpty()) {
                result.append("## ").append(filename).append("\n\n");
                result.append(content).append("\n\n");
            }
        }

        return result.toString();
    }

    private String loadBootstrapFile(String filename) {
        try {
            String filePath = Paths.get(workspace, filename).toString();
            if (Files.exists(Paths.get(filePath))) {
                return fileContentCache.computeIfAbsent(filePath, key -> {
                    try {
                        return Files.readString(Paths.get(filePath));
                    } catch (IOException e) {
                        logger.debug("Failed to load bootstrap file {}: {}", filename, e.getMessage());
                        return "";
                    }
                });
            }
        } catch (Exception e) {
            logger.debug("Failed to load bootstrap file {}: {}", filename, e.getMessage());
        }
        return "";
    }

    private String buildCurrentSessionInfo(String sessionKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current Session\n");
        sb.append("Session: ").append(sessionKey).append("\n");
        sb.append("Time: ").append(Instant.now()).append("\n");
        return sb.toString();
    }

    private String buildSafeConversationSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        if (HISTORICAL_EXECUTION_STATE_PATTERN.matcher(summary).find()) {
            return "Historical summary was omitted from the execution context because it contains prior task state "
                    + "(paths, manifest ids, artifact paths, or pending/done counters). "
                    + "Treat the current user message and current-run tool results as the only executable task state.";
        }
        return summary;
    }

    private String buildManifestSection(String sessionKey, String currentMessage) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return "";
        }
        if (!isExplicitContinuationRequest(currentMessage)) {
            return "# Historical Manifests\n\n"
                    + "Existing manifests from prior turns are not injected into the current execution context. "
                    + "Create a new manifest for the current task unless the user explicitly asks to continue a specific prior task.\n";
        }
        Path manifestDir = Paths.get(workspace, ".jobclaw", "manifests", safePathSegment(sessionKey));
        if (!Files.isDirectory(manifestDir)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("# Active Manifests\n\n");
        try (var stream = Files.list(manifestDir)) {
            List<Path> manifestFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
                        } catch (IOException ignored) {
                            return 0;
                        }
                    })
                    .limit(3)
                    .toList();
            if (manifestFiles.isEmpty()) {
                return "";
            }
            for (Path file : manifestFiles) {
                JsonNode node = OBJECT_MAPPER.readTree(file.toFile());
                JsonNode items = node.path("items");
                int total = 0;
                int pending = 0;
                int running = 0;
                int done = 0;
                int failed = 0;
                if (items.isObject()) {
                    var fields = items.fields();
                    while (fields.hasNext()) {
                        total++;
                        JsonNode item = fields.next().getValue();
                        switch (item.path("status").asText("pending")) {
                            case "running" -> running++;
                            case "done" -> done++;
                            case "failed" -> failed++;
                            default -> pending++;
                        }
                    }
                }
                sb.append("- id=").append(node.path("manifestId").asText(""))
                        .append(" taskKey=").append(node.path("taskKey").asText(""))
                        .append(" total=").append(total)
                        .append(" pending=").append(pending)
                        .append(" running=").append(running)
                        .append(" done=").append(done)
                        .append(" failed=").append(failed);
                String artifactPath = node.path("artifactPath").asText("");
                if (!artifactPath.isBlank()) {
                    sb.append(" artifactPath=").append(artifactPath);
                }
                sb.append("\n");
            }
            sb.append("\nUse `manifest(action='status', manifestId='...', includeItems='pending', limit='10')` to continue an existing batch task.\n");
            return sb.toString();
        } catch (IOException e) {
            logger.debug("Failed to load manifest summaries for session {}: {}", sessionKey, e.getMessage());
            return "";
        }
    }

    private String safePathSegment(String value) {
        return value.replaceAll("[:/\\\\*?\"<>|]", "_");
    }

    private boolean isExplicitContinuationRequest(String currentMessage) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return false;
        }
        String text = currentMessage.toLowerCase();
        return text.contains("继续")
                || text.contains("恢复")
                || text.contains("接着")
                || text.contains("上次")
                || text.contains("之前的任务")
                || text.contains("未完成")
                || text.contains("resume")
                || text.contains("continue")
                || text.contains("previous task");
    }

    public void clearCache() {
        fileContentCache.clear();
    }

    public void clearCacheForFile(String path) {
        fileContentCache.remove(path);
    }
}

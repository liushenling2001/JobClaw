package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.providers.Message;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final String SECTION_SEPARATOR = "\n\n---\n\n";

    private static final String[] BOOTSTRAP_FILES = {
            "AGENTS.md", "SOUL.md", "USER.md", "IDENTITY.md"
    };

    private final Config config;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final SkillsService skillsService;
    private final SummaryService summaryService;
    private final Map<String, String> fileContentCache;
    private final String workspace;

    private int contextWindow;

    public ContextBuilder(Config config,
                          SessionManager sessionManager,
                          SkillsService skillsService,
                          SummaryService summaryService) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.skillsService = skillsService;
        this.summaryService = summaryService;
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
        addSectionIfNotBlank(parts, buildSkillsSection());

        int memoryBudget = calculateMemoryTokenBudget();
        String memoryContext = memoryStore.getMemoryContext(currentMessage, memoryBudget);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            parts.add("# Memory\n\n" + memoryContext);
        }

        String summary = resolveSessionSummary(sessionKey);
        if (summary != null && !summary.isBlank()) {
            parts.add("# Conversation Summary\n\n" + summary);
        }

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

    private String buildSkillsSection() {
        if (skillsService == null) {
            return "";
        }

        String skillsSummary = skillsService.buildSkillsSummary();
        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");

        if (skillsSummary != null && !skillsSummary.trim().isEmpty()) {
            sb.append("## Installed Skills\n\n");
            sb.append("Use `skills(action='invoke', name='skill-name')` to open a skill and get its base path.\n\n");
            sb.append(skillsSummary).append("\n\n");
        }

        appendSkillSelfLearningGuide(sb);
        return sb.toString();
    }

    private void appendSkillSelfLearningGuide(StringBuilder sb) {
        String skillsPath = Paths.get(workspace).toAbsolutePath() + "/skills/";

        sb.append("""
                ## Skill Self-Learning

                You can use the `skills` tool to search, install, create, edit, and remove skills.

                Common operations:
                - `skills(action='list')`
                - `skills(action='invoke', name='...')`
                - `skills(action='search', query='...')`
                - `skills(action='install', repo='owner/repo')`
                - `skills(action='create', name='...', content='...', skill_description='...')`
                - `skills(action='edit', name='...', content='...')`
                - `skills(action='remove', name='...')`

                For script-backed skills:
                1. Invoke the skill to get its base path.
                2. Execute scripts inside that path with `run_command`.
                """);

        sb.append("\nSkills are stored in `").append(skillsPath).append("`.\n");
    }

    private void addSectionIfNotBlank(List<String> parts, String section) {
        if (section != null && !section.trim().isEmpty()) {
            parts.add(section);
        }
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
        sb.append("3. Persist durable memory to workspace memory files when appropriate.\n");
        sb.append("4. To create a reusable specialized agent, use `agent_catalog` to persist the definition.\n");
        sb.append("5. To run an existing persistent agent, use `spawn(agent='agent-name', task='...')`.\n");
        sb.append("6. Do not invent a parallel agent execution flow when `spawn` already fits the task.\n");
        sb.append("\n");
        sb.append("## Persistent Agents\n\n");
        sb.append("- Use `agent_catalog(action='create', ...)` to create a reusable agent.\n");
        sb.append("- Use `agent_catalog(action='list')` or `agent_catalog(action='get', name='...')` to inspect saved agents.\n");
        sb.append("- Use `spawn(agent='saved-agent-name', task='...')` to execute a saved agent.\n");
        sb.append("- Use `spawn(role='coder', task='...')` only for built-in role agents.\n");
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

    public void clearCache() {
        fileContentCache.clear();
    }

    public void clearCacheForFile(String path) {
        fileContentCache.remove(path);
    }
}

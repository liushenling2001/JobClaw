package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolDefinition;
import io.jobclaw.session.SessionManager;
import io.jobclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文构建器，用于构建 Agent 运行所需的完整上下文。
 *
 * 核心职责：
 * - 构建系统提示词：包含身份信息、工具说明、技能摘要、记忆上下文
 * - 加载引导文件：从工作空间加载 AGENTS.md、SOUL.md 等自定义配置
 * - 集成记忆系统：将长期记忆和每日笔记添加到上下文
 * - 管理会话上下文：整合会话摘要和历史对话
 *
 * 上下文层次结构：
 * 1. 身份信息：Agent 名称、当前时间、运行环境、工作空间路径
 * 2. 引导文件：用户自定义的行为指导和身份定义
 * 3. 工具说明：已注册工具的功能描述和使用方法
 * 4. 记忆上下文：长期记忆和近期对话摘要
 * 5. 会话信息：当前会话状态和摘要
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
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;

    private final Map<String, String> fileContentCache;
    private final String workspace;

    /** 上下文窗口大小，用于计算记忆 token 预算 */
    private int contextWindow = AgentConstants.DEFAULT_MAX_TOKENS;

    public ContextBuilder(Config config, SessionManager sessionManager, ToolRegistry toolRegistry) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.toolRegistry = toolRegistry;
        this.fileContentCache = new ConcurrentHashMap<>();
        this.workspace = ConfigLoader.expandHome(config.getAgent().getWorkspace());

        // 初始化记忆存储
        this.memoryStore = new MemoryStore(this.workspace);

        logger.info("ContextBuilder initialized with workspace: {}", this.workspace);
    }

    /**
     * 设置上下文窗口大小，用于动态计算记忆 token 预算。
     *
     * @param contextWindow 上下文窗口 token 数
     */
    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    /**
     * 获取记忆存储实例，供外部组件访问记忆读写能力。
     *
     * @return 记忆存储实例
     */
    public MemoryStore getMemoryStore() {
        return memoryStore;
    }

    /**
     * 为 LLM 构建消息列表。
     *
     * @param sessionKey   会话键
     * @param userContent  用户消息内容
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(String sessionKey, String userContent) {
        List<Message> messages = new ArrayList<>();

        // 构建系统提示词（传入当前消息用于记忆相关性检索）
        String systemPrompt = buildSystemPrompt(sessionKey, userContent);

        logger.debug("System prompt built for session: {}, total_chars: {}",
                sessionKey, systemPrompt.length());

        messages.add(Message.system(systemPrompt));

        // 添加历史记录
        List<Message> history = sessionManager.getHistory(sessionKey);
        if (history != null && !history.isEmpty()) {
            messages.addAll(sanitizeHistory(new ArrayList<>(history)));
        }

        // 添加当前用户消息
        messages.add(Message.user(userContent));

        return messages;
    }

    /**
     * 构建系统提示词。
     *
     * @param sessionKey 会话键
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String sessionKey) {
        return buildSystemPrompt(sessionKey, null);
    }

    /**
     * 构建系统提示词，支持基于当前消息的记忆相关性检索。
     *
     * @param sessionKey    会话键
     * @param currentMessage 当前用户消息（用于记忆相关性匹配）
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String sessionKey, String currentMessage) {
        List<String> parts = new ArrayList<>();

        // 1. 核心身份部分
        parts.add(getIdentity());

        // 2. 引导文件
        addSectionIfNotBlank(parts, loadBootstrapFiles());

        // 3. 工具部分
        addSectionIfNotBlank(parts, buildToolsSection());

        // 4. 记忆上下文（带 token 预算控制和相关性检索）
        int memoryBudget = calculateMemoryTokenBudget();
        String memoryContext = memoryStore.getMemoryContext(currentMessage, memoryBudget);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            parts.add("# Memory\n\n" + memoryContext);
        }

        // 5. 会话摘要
        String summary = sessionManager.getSummary(sessionKey);
        if (summary != null && !summary.isEmpty()) {
            parts.add("# Conversation Summary\n\n" + summary);
        }

        // 6. 当前会话信息
        parts.add(buildCurrentSessionInfo(sessionKey));

        return String.join(SECTION_SEPARATOR, parts);
    }

    /**
     * 根据上下文窗口大小计算记忆 token 预算。
     *
     * @return 记忆 token 预算
     */
    private int calculateMemoryTokenBudget() {
        int budget = contextWindow * AgentConstants.MEMORY_TOKEN_BUDGET_PERCENTAGE / 100;
        return Math.max(AgentConstants.MEMORY_MIN_TOKEN_BUDGET,
                Math.min(AgentConstants.MEMORY_MAX_TOKEN_BUDGET, budget));
    }

    /**
     * 添加非空部分到列表。
     *
     * @param parts   部分列表
     * @param section 要添加的部分内容
     */
    private void addSectionIfNotBlank(List<String> parts, String section) {
        if (section != null && !section.trim().isEmpty()) {
            parts.add(section);
        }
    }

    /**
     * 获取 Agent 身份和基本信息。
     *
     * @return 身份信息字符串
     */
    private String getIdentity() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));
        String workspacePath = Paths.get(workspace).toAbsolutePath().toString();
        String runtime = System.getProperty("os.name") + " " + System.getProperty("os.arch") +
                ", Java " + System.getProperty("java.version");

        StringBuilder sb = new StringBuilder();
        sb.append("# JobClaw 🦞\n\n");
        sb.append("你是 JobClaw，一个有用的 AI 助手。\n\n");
        sb.append("## 当前时间\n");
        sb.append(now).append("\n\n");
        sb.append("## 运行环境\n");
        sb.append(runtime).append("\n\n");
        sb.append("## 工作空间\n");
        sb.append("你的工作空间位于：").append(workspacePath).append("\n");
        sb.append("- 记忆：").append(workspacePath).append("/memory/MEMORY.md\n");
        sb.append("- 每日笔记：").append(workspacePath).append("/memory/YYYYMM/YYYYMMDD.md\n\n");

        sb.append("## 重要规则\n\n");
        sb.append("1. **始终使用工具** - 当你需要执行操作（安排提醒、发送消息、执行命令等）时，你必须调用适当的工具。不要只是说你会做或假装做。\n\n");
        sb.append("2. **乐于助人和准确** - 使用工具时，简要说明你在做什么。\n\n");
        sb.append("3. **记忆** - 记住某些内容时，写入工作空间的记忆文件。\n");

        return sb.toString();
    }

    /**
     * 构建系统提示词的工具部分。
     *
     * @return 工具部分字符串，无工具时返回空字符串
     */
    private String buildToolsSection() {
        if (toolRegistry == null || toolRegistry.getSummaries().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        sb.append("**重要**: 你必须使用工具来执行操作。不要假装执行命令或安排任务。\n\n");
        sb.append("你可以访问以下工具:\n\n");
        sb.append(toolRegistry.getSummaries());

        return sb.toString();
    }

    /**
     * 从工作空间加载引导文件。
     *
     * @return 引导文件内容，无文件时返回空字符串
     */
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

    /**
     * 加载单个引导文件。
     *
     * @param filename 文件名
     * @return 文件内容，失败时返回空字符串
     */
    private String loadBootstrapFile(String filename) {
        try {
            String filePath = Paths.get(workspace, filename).toString();
            if (Files.exists(Paths.get(filePath))) {
                // 使用缓存减少重复读取
                return fileContentCache.computeIfAbsent(filePath, key -> {
                    try {
                        return Files.readString(Paths.get(filePath));
                    } catch (IOException e) {
                        logger.debug("Failed to load bootstrap file: {}, error: {}",
                                filename, e.getMessage());
                        return "";
                    }
                });
            }
        } catch (Exception e) {
            logger.debug("Failed to load bootstrap file: {}, error: {}",
                    filename, e.getMessage());
        }
        return "";
    }

    /**
     * 构建当前会话信息。
     *
     * @param sessionKey 会话键
     * @return 会话信息字符串
     */
    private String buildCurrentSessionInfo(String sessionKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current Session\n");
        sb.append("Session: ").append(sessionKey).append("\n");
        sb.append("Time: ").append(Instant.now()).append("\n");
        return sb.toString();
    }

    /**
     * 清理历史消息，确保 tool 消息前面有对应的 assistant 消息。
     *
     * @param history 原始历史消息列表
     * @return 清理后的历史消息列表
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history.isEmpty()) {
            return history;
        }

        // 找到第一条非孤立 tool 消息的位置
        int startIndex = 0;
        while (startIndex < history.size() && "tool".equals(history.get(startIndex).getRole())) {
            startIndex++;
        }

        if (startIndex == 0) {
            return history;
        }

        if (startIndex > 0) {
            logger.warn("Skipped orphaned tool messages at history start: count={}", startIndex);
        }

        return new ArrayList<>(history.subList(startIndex, history.size()));
    }

    /**
     * 清除文件内容缓存。
     */
    public void clearCache() {
        fileContentCache.clear();
    }

    /**
     * 清除指定文件的缓存。
     *
     * @param path 文件路径
     */
    public void clearCacheForFile(String path) {
        fileContentCache.remove(path);
    }
}

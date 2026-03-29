package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.providers.Message;
import io.jobclaw.providers.ToolDefinition;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillsService;
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
 * - 构建系统提示词：包含身份信息、引导文件、技能摘要、记忆上下文
 * - 加载引导文件：从工作空间加载 AGENTS.md、SOUL.md 等自定义配置
 * - 集成记忆系统：将长期记忆和每日笔记添加到上下文
 * - 管理会话上下文：整合会话摘要和历史对话
 *
 * 上下文层次结构：
 * 1. 身份信息：Agent 名称、当前时间、运行环境、工作空间路径
 * 2. 引导文件：用户自定义的行为指导和身份定义
 * 3. 技能摘要：已安装技能的简要说明和位置信息
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
    private final MemoryStore memoryStore;
    private final SkillsService skillsService;

    private final Map<String, String> fileContentCache;
    private final String workspace;

    /** 上下文窗口大小，用于计算记忆 token 预算 */
    private int contextWindow;

    public ContextBuilder(Config config, SessionManager sessionManager, SkillsService skillsService) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.skillsService = skillsService;
        this.fileContentCache = new ConcurrentHashMap<>();
        this.workspace = ConfigLoader.expandHome(config.getAgent().getWorkspace());
        this.contextWindow = config.getAgent().getContextWindow();

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

        // 添加历史记录（带 token 预算控制）
        List<Message> history = sessionManager.getHistory(sessionKey);
        if (history != null && !history.isEmpty()) {
            List<Message> sanitizedHistory = sanitizeHistory(new ArrayList<>(history));
            List<Message> filteredHistory = filterHistoryByTokenBudget(sanitizedHistory);
            messages.addAll(filteredHistory);
        }

        // 添加当前用户消息
        messages.add(Message.user(userContent));

        return messages;
    }

    /**
     * 根据 token 预算过滤历史消息，避免超出上下文窗口。
     *
     * 策略：
     * - 保留最近的消息，直到达到 token 预算
     * - 优先保留 user 和 assistant 消息
     * - 如果单条消息超过预算则跳过
     *
     * @param history 原始历史消息列表
     * @return 过滤后的历史消息列表
     */
    private List<Message> filterHistoryByTokenBudget(List<Message> history) {
        if (history.isEmpty()) {
            return history;
        }

        // 计算历史消息的 token 预算（上下文窗口的 30% 用于历史对话）
        int historyTokenBudget = contextWindow * 30 / 100;
        int maxMessageTokens = contextWindow / 4; // 单条消息不超过 1/4 上下文窗口

        List<Message> filtered = new ArrayList<>();
        int usedTokens = 0;

        // 从后向前遍历（保留最近的 messages）
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            int msgTokens = estimateToken(msg.getContent());

            // 跳过超过单条限制的消息
            if (msgTokens > maxMessageTokens) {
                logger.debug("Skipped history message: tokens={}, exceeds limit={}",
                        msgTokens, maxMessageTokens);
                continue;
            }

            // 如果添加此消息会超出预算，停止
            if (usedTokens + msgTokens > historyTokenBudget) {
                logger.debug("History token budget reached: used={}, budget={}, remaining messages={}",
                        usedTokens, historyTokenBudget, i);
                break;
            }

            filtered.add(msg);
            usedTokens += msgTokens;
        }

        // 反转列表（恢复原始顺序）
        Collections.reverse(filtered);

        logger.info("Filtered history: original={}, filtered={}, tokens={}",
                history.size(), filtered.size(), usedTokens);

        return filtered;
    }

    /**
     * 估算单个消息的 Token 数。
     *
     * @param content 消息内容
     * @return 估算的 Token 数
     */
    private int estimateToken(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int englishChars = 0;
        for (char c : content.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                englishChars++;
            }
        }
        return chineseChars + englishChars / 4;
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

        // 3. 技能摘要部分
        addSectionIfNotBlank(parts, buildSkillsSection());

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
        int budget = contextWindow * config.getAgent().getMemoryTokenBudgetPercentage() / 100;
        return Math.max(config.getAgent().getMemoryMinTokenBudget(),
                Math.min(config.getAgent().getMemoryMaxTokenBudget(), budget));
    }

    /**
     * 构建技能摘要部分。
     *
     * 生成已安装技能的简要说明，采用渐进式披露策略：
     * - 只显示技能名称、描述和位置
     * - 完整内容需要使用 skills 工具调用
     * - 引导 AI 自主学习：安装社区技能、创建新技能、迭代优化已有技能
     *
     * @return 技能摘要字符串（即使没有技能也返回自主学习引导）
     */
    private String buildSkillsSection() {
        if (skillsService == null) {
            return "";
        }

        String skillsSummary = skillsService.buildSkillsSummary();

        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");

        // 已安装技能摘要
        if (skillsSummary != null && !skillsSummary.trim().isEmpty()) {
            sb.append("## 已安装技能\n\n");
            sb.append("以下技能扩展了你的能力。");
            sb.append("使用 `skills(action='invoke', name='技能名')` 调用技能，获取完整内容和 base-path（可用于执行技能目录下的脚本）。\n\n");
            sb.append(skillsSummary);
            sb.append("\n\n");
        }

        // AI 自主学习技能的引导
        appendSkillSelfLearningGuide(sb);

        return sb.toString();
    }

    /**
     * 追加技能自主学习引导。
     */
    private void appendSkillSelfLearningGuide(StringBuilder sb) {
        String skillsPath = Paths.get(workspace).toAbsolutePath() + "/skills/";

        sb.append("""
                ## 技能自主学习

                你有能力使用 `skills` 工具**自主学习和管理技能**。
                这意味着你不局限于预安装的技能——你可以随着时间增长你的能力。

                ### 何时学习新技能

                - 当你遇到现有技能无法覆盖的任务时，先**搜索 GitHub** 上是否有现成的技能可以安装。
                - 当用户提到社区技能或包含有用技能的 GitHub 仓库时，直接**安装它**。
                - 如果搜索不到合适的技能，考虑**创建新技能**来处理它。
                - 当你发现自己重复执行类似的多步操作时，**将模式提取为可复用的技能**。
                - 当现有技能可以根据新经验改进时，**编辑它**使其更好。

                ### 如何管理技能

                使用 `skills` 工具执行以下操作：
                - `skills(action='list')` — 查看所有已安装技能
                - `skills(action='invoke', name='...')` — **调用技能并获取其基础路径**（用于带脚本的技能）
                - `skills(action='search', query='...')` — **从可信技能市场搜索可用的技能**（按功能描述搜索）
                - `skills(action='install', repo='owner/repo')` — 从 GitHub 安装指定技能
                - `skills(action='create', name='...', content='...', skill_description='...')` — 根据经验创建新技能
                - `skills(action='edit', name='...', content='...')` — 改进现有技能
                - `skills(action='remove', name='...')` — 删除不再需要的技能

                ### 调用带脚本的技能

                当技能包含可执行脚本（如 Python 文件）时，使用 `invoke` 而非 `show`：
                1. 调用 `skills(action='invoke', name='技能名')` 获取技能的基础路径和指令
                2. 响应中包含指向技能目录的 `<base-path>`
                3. 使用基础路径执行脚本，例如：`run_command(command='python3 {base-path}/script.py 参数 1')`

                带脚本技能的示例工作流：
                ```
                1. skills(action='invoke', name='pptx')  → 获取基础路径：/path/to/skills/pptx/
                2. run_command(command='python3 /path/to/skills/pptx/create_pptx.py output.pptx')
                ```

                ### 创建可学习技能

                创建技能时，将其编写为带有 YAML frontmatter 的 **Markdown 指令手册**。好的技能应包含：
                1. 清晰描述技能的功能
                2. 逐步执行的指令
                3. （可选）在哪里找到和安装依赖或相关社区技能
                4. 何时以及如何使用该技能的示例

                """);

        sb.append("你创建的技能保存在 `").append(skillsPath).append("`，将在未来的对话中自动可用。\n");
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

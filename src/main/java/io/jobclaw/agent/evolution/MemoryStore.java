package io.jobclaw.agent.evolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jobclaw.agent.AgentConstants;
import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.workspace.WorkspaceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 自进化记忆存储系统，持久化的 Agent 记忆管理。
 *
 * 提供三层记忆架构：
 * - 结构化长期记忆：memory/MEMORIES.json，带元数据的记忆条目（重要性、标签、衰减）
 * - 传统长期记忆：memory/MEMORY.md，兼容旧格式的 Markdown 记忆（Agent 可直接编辑）
 * - 每日笔记：memory/YYYYMM/YYYYMMDD.md，按日期组织的日常记录
 *
 * 核心能力：
 * - Token 预算控制：根据上下文窗口分配记忆预算，防止上下文爆炸
 * - 相关性检索：根据当前对话内容，优先选取相关记忆
 * - 重要性排序：相关性准入后考虑重要性与时间衰减，访问次数仅用于诊断
 * - 结构化存储：每条记忆带有元数据，支持自动进化
 */
public class MemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(MemoryStore.class);

    /** 结构化记忆文件名 */
    private static final String MEMORIES_FILE = "MEMORIES.json";

    /** 归档记忆文件名 */
    private static final String ARCHIVE_FILE = "MEMORIES_ARCHIVE.json";

    /** 默认记忆 token 预算（当调用方未指定时） */
    private static final int DEFAULT_MEMORY_TOKEN_BUDGET = 1024;

    private final String workspace;
    private final String memoryDir;
    private final String memoryFile;
    private final String memoriesJsonFile;
    private final String archiveJsonFile;
    private final ObjectMapper objectMapper;
    private final WorkspaceRegistry workspaceRegistry;

    /** 内存中的结构化记忆缓存，线程安全 */
    private final CopyOnWriteArrayList<MemoryEntry> entries;

    /**
     * 构造 MemoryStore 实例。
     *
     * @param workspace 工作空间根路径
     */
    public MemoryStore(String workspace) {
        this(workspace, null);
    }

    public MemoryStore(String workspace, WorkspaceRegistry workspaceRegistry) {
        this.workspace = workspace;
        this.workspaceRegistry = workspaceRegistry;
        this.memoryDir = Paths.get(workspace, "memory").toString();
        this.memoryFile = Paths.get(memoryDir, "MEMORY.md").toString();
        this.memoriesJsonFile = Paths.get(memoryDir, MEMORIES_FILE).toString();
        this.archiveJsonFile = Paths.get(memoryDir, ARCHIVE_FILE).toString();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.entries = new CopyOnWriteArrayList<>();

        ensureDirectoryExists(Paths.get(memoryDir));
        loadEntries();
    }

    // ==================== 结构化记忆操作 ====================

    /**
     * 添加一条结构化记忆。
     *
     * @param content    记忆内容
     * @param importance 重要性评分 (0.0 ~ 1.0)
     * @param tags       标签列表
     * @param source     来源标识（如 session_summary, heartbeat, user_explicit, evolution）
     */
    public synchronized void addEntry(String content, double importance, List<String> tags, String source) {
        addEntry(content, importance, tags, source, "project", scopeFor(null));
    }

    public synchronized MemoryEntry addEntry(String content, double importance, List<String> tags, String source,
                                              String scope, MemoryScope owner) {
        if (content == null || content.isBlank() || scope == null || !Set.of("user", "project", "agent").contains(scope)) {
            throw new IllegalArgumentException("Memory requires non-empty content and a valid scope");
        }
        if (!"user".equals(scope) && (owner == null || owner.workspace() == null)) {
            throw new IllegalArgumentException("Project and agent memories require a workspace");
        }
        MemoryEntry entry = new MemoryEntry(content, importance, tags, source);
        entry.setScope(scope);
        entry.setWorkspace("user".equals(scope) ? null : owner.workspace());
        entry.setAgentId("agent".equals(scope) ? owner.agentId() : null);
        List<MemoryEntry> updated = new ArrayList<>(entries);
        updated.add(entry);
        commitEntries(updated);
        logger.debug("Added memory entry: source={}, importance={}, tags={}",
                source, importance, tags != null ? tags.toString() : "[]");
        return copy(entry);
    }

    public MemoryScope scopeFor(String sessionId) {
        var current = AgentExecutionContext.getCurrentScope();
        boolean currentSession = current != null && (sessionId == null || sessionId.equals(current.sessionKey()));
        String directory = currentSession ? current.workingDirectory() : null;
        String key = sessionId != null ? sessionId : AgentExecutionContext.getCurrentSessionKey();
        if ((directory == null || directory.isBlank()) && key != null && workspaceRegistry != null) {
            directory = workspaceRegistry.resolveWorkingDirectory(key);
        }
        String agent = currentSession && current.definition() != null ? current.definition().getCode() : "default";
        return new MemoryScope(directory == null || directory.isBlank() ? workspace : directory, agent);
    }

    public MemoryScope defaultScope() {
        return new MemoryScope(workspace, "default");
    }

    public synchronized List<MemoryEntry> getEntries(MemoryScope scope) {
        return entries.stream().filter(entry -> visible(entry, scope)).map(this::copy).toList();
    }

    private boolean visible(MemoryEntry entry, MemoryScope scope) {
        return scope.permits(entry.getScope(), entry.getWorkspace(), entry.getAgentId());
    }

    private MemoryEntry copy(MemoryEntry entry) {
        return objectMapper.convertValue(entry, MemoryEntry.class);
    }

    public synchronized MemoryEntry updateEntry(String id, String content, Double importance, MemoryScope scope) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Updated memory content must not be empty");
        }
        for (MemoryEntry entry : entries) {
            if (Objects.equals(id, entry.getId()) && visible(entry, scope)) {
                MemoryEntry updated = copy(entry);
                Instant now = Instant.now();
                updated.getRevisions().add(new MemoryEntry.Revision(entry.getContent(), entry.getImportance(), now));
                updated.setContent(content);
                if (importance != null) {
                    updated.setImportance(importance);
                }
                updated.setUpdatedAt(now);
                List<MemoryEntry> next = new ArrayList<>(entries);
                next.set(next.indexOf(entry), updated);
                commitEntries(next);
                return copy(updated);
            }
        }
        return null;
    }

    public synchronized MemoryEntry removeEntry(String id, MemoryScope scope) {
        if (entries.stream().noneMatch(entry -> Objects.equals(id, entry.getId()) && visible(entry, scope))) {
            return null;
        }
        return removeEntry(id);
    }

    /**
     * 获取所有结构化记忆条目（只读副本）。
     *
     * @return 记忆条目列表
     */
    public synchronized List<MemoryEntry> getEntries() {
        return entries.stream().map(this::copy).toList();
    }

    /**
     * 替换所有结构化记忆条目（用于进化引擎整合后的批量更新）。
     *
     * @param newEntries 新的记忆条目列表
     */
    public synchronized void replaceEntries(List<MemoryEntry> newEntries) {
        List<MemoryEntry> next = newEntries == null ? List.of() : newEntries.stream()
                .map(this::copy).peek(this::normalizeLegacyScope).toList();
        commitEntries(next);
        logger.info("Replaced all memory entries: count={}", entries.size());
    }

    /**
     * 移除指定 ID 的记忆条目。
     *
     * @param entryId 要移除的条目 ID
     * @return 被移除的条目，未找到返回 null
     */
    public synchronized MemoryEntry removeEntry(String entryId) {
        for (MemoryEntry entry : entries) {
            if (entryId.equals(entry.getId())) {
                List<MemoryEntry> next = new ArrayList<>(entries);
                next.remove(entry);
                commitEntries(next);
                return copy(entry);
            }
        }
        return null;
    }

    /**
     * 归档低分记忆条目到归档文件。
     *
     * @param entriesToArchive 要归档的条目列表
     */
    public synchronized void archiveEntries(List<MemoryEntry> entriesToArchive) {
        if (entriesToArchive == null || entriesToArchive.isEmpty()) {
            return;
        }

        // 读取现有归档
        List<MemoryEntry> archived = loadEntriesFromFile(archiveJsonFile);
        archived.addAll(entriesToArchive);

        // 写入归档文件
        saveEntriesToFile(archiveJsonFile, archived);

        // 从活跃记忆中移除
        List<MemoryEntry> next = new ArrayList<>(entries);
        next.removeAll(entriesToArchive);
        commitEntries(next);

        logger.info("Archived memory entries: count={}", entriesToArchive.size());
    }

    // ==================== 记忆上下文构建（核心） ====================

    /**
     * 获取格式化的记忆上下文，带 token 预算控制和相关性检索。
     *
     * 预算分配策略：
     * - 50% 给结构化记忆（按相关性 + 重要性排序）
     * - 30% 给每日笔记（最近 1-3 天，按 token 预算动态调整天数）
     * - 20% 给传统 MEMORY.md（截断到预算内）
     *
     * @param currentMessage 当前用户消息，用于相关性匹配（可为 null）
     * @param tokenBudget    记忆部分的 token 预算上限
     * @return 格式化的记忆上下文，如果没有记忆则返回空字符串
     */
    public String getMemoryContext(String currentMessage, int tokenBudget) {
        return getMemoryContext(currentMessage, tokenBudget, scopeFor(null));
    }

    public synchronized String getMemoryContext(String currentMessage, int tokenBudget, MemoryScope scope) {
        if (tokenBudget <= 0) {
            tokenBudget = DEFAULT_MEMORY_TOKEN_BUDGET;
        }

        List<String> keywords = extractKeywords(currentMessage);
        List<String> parts = new ArrayList<>();

        // 1. 结构化记忆（按相关性 + 重要性排序，占 50% 预算）
        int structuredBudget = (int) (tokenBudget * AgentConstants.STRUCTURED_MEMORY_TOKEN_RATIO);
        String structuredSection = buildStructuredMemorySection(keywords, structuredBudget, scope);
        if (structuredSection != null && !structuredSection.isEmpty()) {
            parts.add(structuredSection);
        }

        // 2. 每日笔记（占 30% 预算）
        int dailyNotesBudget = (int) (tokenBudget * AgentConstants.DAILY_NOTES_TOKEN_RATIO);
        boolean legacyVisible = Objects.equals(scope.workspace(), defaultScope().workspace());
        String dailyNotesSection = buildDailyNotesSection(dailyNotesBudget, keywords, scope);
        if (dailyNotesSection != null && !dailyNotesSection.isEmpty()) {
            parts.add(dailyNotesSection);
        }

        // 3. 传统 MEMORY.md（占 20% 预算）
        int legacyBudget = (int) (tokenBudget * AgentConstants.LEGACY_MEMORY_TOKEN_RATIO);
        String legacySection = legacyVisible ? buildLegacyMemorySection(legacyBudget, keywords) : "";
        if (legacySection != null && !legacySection.isEmpty()) {
            parts.add(legacySection);
        }

        if (parts.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 无参版本只允许明确的全局用户偏好，不填充无关的任务记忆。
     *
     * @return 格式化的记忆上下文
     */
    public String getMemoryContext() {
        return getMemoryContext(null, DEFAULT_MEMORY_TOKEN_BUDGET);
    }

    /**
     * 构建结构化记忆部分。
     *
     * 按综合得分（重要性 × 衰减 × 相关性加成）排序，
     * 从高到低选取记忆条目，直到用完 token 预算。
     *
     * @param keywords    当前对话的关键词
     * @param tokenBudget 此部分的 token 预算
     * @return 格式化的结构化记忆字符串
     */
    private String buildStructuredMemorySection(List<String> keywords, int tokenBudget, MemoryScope scope) {
        if (entries.isEmpty() || tokenBudget <= 0) {
            return "";
        }

        // 计算每条记忆的综合得分（基础分 + 相关性加成）
        List<ScoredEntry> scoredEntries = entries.stream()
                .filter(entry -> visible(entry, scope))
                .filter(entry -> entry.computeRelevance(keywords) > 0 || isExplicitPreference(entry))
                .map(entry -> {
                    double baseScore = entry.computeScore();
                    int relevance = entry.computeRelevance(keywords);
                    double finalScore = relevance > 0
                            ? baseScore * (1.0 + relevance * AgentConstants.RELEVANCE_BOOST_MULTIPLIER)
                            : baseScore;
                    return new ScoredEntry(entry, finalScore);
                })
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .collect(Collectors.toList());

        // 按预算选取记忆
        StringBuilder section = new StringBuilder("## Key Memories\n\n");
        int usedTokens = estimateTokens(section.toString());
        int selectedCount = 0;

        for (ScoredEntry scored : scoredEntries) {
            String entryText = formatEntryForContext(scored.entry());
            int entryTokens = estimateTokens(entryText);

            // 单条记忆超过限制则截断
            if (entryTokens > AgentConstants.MAX_SINGLE_ENTRY_TOKENS) {
                int maxChars = AgentConstants.MAX_SINGLE_ENTRY_TOKENS * 4;
                entryText = entryText.substring(0, Math.min(entryText.length(), maxChars)) + "...";
                entryTokens = AgentConstants.MAX_SINGLE_ENTRY_TOKENS;
            }

            if (usedTokens + entryTokens > tokenBudget) {
                break;
            }

            section.append(entryText).append("\n");
            usedTokens += entryTokens;
            selectedCount++;

            // 记录访问
            scored.entry().recordAccess();
        }

        if (selectedCount == 0) {
            return "";
        }

        // 如果有未选中的记忆，提示 Agent
        int totalEntries = scoredEntries.size();
        if (selectedCount < totalEntries) {
            section.append(String.format("\n_(%d of %d memories shown, filtered by relevance and importance)_\n",
                    selectedCount, totalEntries));
        }

        // Persist under the same store lock; no stale asynchronous snapshot can overwrite a write.
        saveEntries();

        return section.toString();
    }

    /**
     * 构建每日笔记部分，根据 token 预算动态调整天数。
     *
     * @param tokenBudget 此部分的 token 预算
     * @return 格式化的每日笔记字符串
     */
    private String buildDailyNotesSection(int tokenBudget, List<String> keywords, MemoryScope scope) {
        if (tokenBudget <= 0) {
            return "";
        }

        StringBuilder section = new StringBuilder("## Recent Activity\n\n");
        int usedTokens = estimateTokens(section.toString());
        LocalDate today = LocalDate.now();

        // 从今天开始，逐天加载，直到预算用完或最多 7 天
        int maxDays = 7;
        boolean hasContent = false;

        for (int dayOffset = 0; dayOffset < maxDays; dayOffset++) {
            LocalDate date = today.minusDays(dayOffset);
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String monthDir = dateStr.substring(0, 6);
            String filePath = scopedNotesDirectory(scope).resolve(monthDir).resolve(dateStr + ".md").toString();

            try {
                if (!Files.exists(Paths.get(filePath))) {
                    continue;
                }

                String noteContent = Files.readString(Paths.get(filePath));
                noteContent = relevantParagraphs(noteContent, keywords);
                if (noteContent.isBlank()) {
                    continue;
                }
                int noteTokens = estimateTokens(noteContent);

                if (usedTokens + noteTokens > tokenBudget) {
                    // 预算不够放完整笔记，截断到剩余预算
                    int remainingTokens = tokenBudget - usedTokens;
                    if (remainingTokens > 50) {
                        int maxChars = remainingTokens * 4;
                        String truncated = noteContent.substring(0, Math.min(noteContent.length(), maxChars));
                        section.append(truncated).append("...\n");
                        hasContent = true;
                    }
                    break;
                }

                section.append(noteContent).append("\n\n---\n\n");
                usedTokens += noteTokens;
                hasContent = true;

            } catch (IOException e) {
                // 忽略单个笔记的读取错误
            }
        }

        return hasContent ? section.toString() : "";
    }

    /**
     * 构建传统 MEMORY.md 部分，截断到 token 预算内。
     *
     * @param tokenBudget 此部分的 token 预算
     * @return 格式化的传统记忆字符串
     */
    private String buildLegacyMemorySection(int tokenBudget, List<String> keywords) {
        if (tokenBudget <= 0) {
            return "";
        }

        String longTerm = relevantParagraphs(readLongTerm(), keywords);
        if (longTerm == null || longTerm.isEmpty()) {
            return "";
        }

        int longTermTokens = estimateTokens(longTerm);
        if (longTermTokens <= tokenBudget) {
            return "## Long-term Memory\n\n" + longTerm;
        }

        // 截断到预算内
        int headerTokens = estimateTokens("## Long-term Memory\n\n");
        int contentBudget = tokenBudget - headerTokens;
        if (contentBudget <= 0) {
            return "";
        }

        int maxChars = contentBudget * 4;
        String truncated = longTerm.substring(0, Math.min(longTerm.length(), maxChars));

        // 尝试在最后一个换行处截断，避免截断到半句话
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > truncated.length() / 2) {
            truncated = truncated.substring(0, lastNewline);
        }

        return "## Long-term Memory\n\n" + truncated +
                "\n\n_(truncated to fit context budget)_";
    }

    // ==================== 关键词提取 ====================

    /**
     * 从消息中提取关键词，用于记忆相关性匹配。
     *
     * @param message 用户消息
     * @return 关键词列表
     */
    private List<String> extractKeywords(String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }

        // 中英文混合分词：按空格、标点分割
        String[] tokens = message.split("[\\s,，。！？!?;；:：、()（）\\[\\]{}\"']+");

        Set<String> stopWords = Set.of(
                // 英文停用词
                "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "can", "shall", "to", "of", "in", "for",
                "on", "with", "at", "by", "from", "as", "into", "about", "it", "its",
                "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
                "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
                "what", "which", "who", "when", "where", "how", "not", "no", "but", "and",
                "or", "if", "then", "so", "just", "also", "very", "too", "here", "there",
                // 中文停用词
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
                "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
                "没有", "看", "好", "自己", "这", "他", "她", "它", "吗", "吧", "呢",
                "啊", "哦", "嗯", "那", "还", "把", "被", "让", "给", "从", "对"
        );

        return Arrays.stream(tokens)
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !stopWords.contains(token.toLowerCase()))
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }

    // ==================== 格式化 ====================

    /**
     * 将记忆条目格式化为上下文中的文本。
     *
     * @param entry 记忆条目
     * @return 格式化的文本
     */
    private String formatEntryForContext(MemoryEntry entry) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("- ");

        // 添加标签前缀
        if (entry.getTags() != null && !entry.getTags().isEmpty()) {
            String tagStr = entry.getTags().stream()
                    .map(tag -> "[" + tag + "]")
                    .collect(Collectors.joining(""));
            formatted.append(tagStr).append(" ");
        }

        formatted.append(entry.getContent());
        return formatted.toString();
    }

    // ==================== 传统接口（向后兼容） ====================

    /**
     * 读取传统长期记忆文件 (MEMORY.md)。
     *
     * @return 长期记忆内容，失败时返回空字符串
     */
    public String readLongTerm() {
        try {
            if (Files.exists(Paths.get(memoryFile))) {
                return Files.readString(Paths.get(memoryFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read long-term memory: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 写入传统长期记忆文件 (MEMORY.md)。
     *
     * @param content 要写入的长期记忆内容
     */
    public synchronized void writeLongTerm(String content) {
        try {
            writeAtomically(Path.of(memoryFile), content);
            logger.debug("Wrote long-term memory");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write long-term memory", e);
        }
    }

    /**
     * 读取今日笔记。
     *
     * @return 今日笔记内容，失败时返回空字符串
     */
    public String readToday() {
        String todayFile = getTodayFile();
        try {
            if (Files.exists(Paths.get(todayFile))) {
                return Files.readString(Paths.get(todayFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read today's note: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 追加内容到今日笔记。
     *
     * @param content 要追加的内容
     */
    public void appendToday(String content) {
        appendToday(content, scopeFor(null));
    }

    public synchronized void appendToday(String content, MemoryScope scope) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String todayFile = scopedNotesDirectory(scope).resolve(date.substring(0, 6)).resolve(date + ".md").toString();

        try {
            Path monthDirPath = Paths.get(todayFile).getParent();
            if (monthDirPath != null) {
                Files.createDirectories(monthDirPath);
            }

            String existingContent = "";
            if (Files.exists(Paths.get(todayFile))) {
                existingContent = Files.readString(Paths.get(todayFile));
            }

            String newContent;
            if (existingContent.isEmpty()) {
                String header = "# " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "\n\n";
                newContent = header + content;
            } else {
                newContent = existingContent + "\n\n" + content;
            }

            writeAtomically(Path.of(todayFile), newContent);
            logger.debug("Appended to today's note");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append daily memory note", e);
        }
    }

    // ==================== 持久化 ====================

    /**
     * 从 MEMORIES.json 加载结构化记忆到内存。
     */
    private void loadEntries() {
        List<MemoryEntry> loaded = loadEntriesFromFile(memoriesJsonFile);
        loaded.forEach(this::normalizeLegacyScope);
        entries.addAll(loaded);
        if (!loaded.isEmpty()) {
            logger.info("Loaded memory entries: count={}", loaded.size());
        }
    }

    /**
     * 将内存中的结构化记忆保存到 MEMORIES.json。
     */
    private void saveEntries() {
        saveEntriesToFile(memoriesJsonFile, new ArrayList<>(entries));
    }

    private void commitEntries(List<MemoryEntry> next) {
        saveEntriesToFile(memoriesJsonFile, next);
        entries.clear();
        entries.addAll(next);
    }

    /**
     * 旧记录没有范围时保守绑定默认工作区，不推断为全局经验。
     */
    private void normalizeLegacyScope(MemoryEntry entry) {
        if (entry.getScope() == null || entry.getScope().isBlank()) {
            boolean explicitUser = "user_explicit".equals(entry.getSource()) && entry.getTags() != null
                    && entry.getTags().contains("user") && !entry.getTags().contains("project")
                    && !entry.getTags().contains("agent");
            boolean legacyAgent = entry.getTags() != null && entry.getTags().contains("agent");
            entry.setScope(explicitUser ? "user" : legacyAgent ? "agent" : "project");
        }
        if (!"user".equals(entry.getScope()) && entry.getWorkspace() == null) {
            entry.setWorkspace(defaultScope().workspace());
        }
        if ("agent".equals(entry.getScope()) && entry.getAgentId() == null) {
            entry.setAgentId("default");
        }
    }

    private Path scopedNotesDirectory(MemoryScope scope) {
        // Old mixed-workspace daily notes remain on disk, but are not automatically injected.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    Objects.requireNonNull(scope.workspace()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Path.of(memoryDir, "workspaces", HexFormat.of().formatHex(digest), "notes");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isExplicitPreference(MemoryEntry entry) {
        return "user".equals(entry.getScope()) && "user_explicit".equals(entry.getSource())
                && entry.getTags() != null && entry.getTags().contains("preference");
    }

    private String relevantParagraphs(String text, List<String> keywords) {
        if (text == null || keywords.isEmpty()) {
            return "";
        }
        return Arrays.stream(text.split("\\R\\s*\\R"))
                .filter(part -> MemoryRelevance.score(String.join(" ", keywords), part) > 0)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 从指定文件加载记忆条目列表。
     *
     * @param filePath JSON 文件路径
     * @return 记忆条目列表，失败时返回空列表
     */
    private List<MemoryEntry> loadEntriesFromFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                if (json != null && !json.trim().isEmpty()) {
                    return objectMapper.readValue(json, new TypeReference<List<MemoryEntry>>() {});
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load memory: " + filePath, e);
        }
        return new ArrayList<>();
    }

    /**
     * 将记忆条目列表保存到指定文件。
     *
     * @param filePath JSON 文件路径
     * @param entriesToSave 要保存的条目列表
     */
    private void saveEntriesToFile(String filePath, List<MemoryEntry> entriesToSave) {
        try {
            String json = objectMapper.writeValueAsString(entriesToSave);
            writeAtomically(Path.of(filePath), json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save memory: " + filePath, e);
        }
    }

    private void writeAtomically(Path path, String content) throws IOException {
        Path temporary = null;
        Path target = path.toAbsolutePath();
        try {
            temporary = Files.createTempFile(target.getParent(), "memory-", ".tmp");
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    logger.warn("Failed to remove temporary memory file: {}", temporary);
                }
            }
        }
    }

    // ==================== 统计与诊断 ====================

    /**
     * 获取记忆系统的统计信息。
     *
     * @return 包含各项统计数据的 Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("structured_entries", entries.size());
        stats.put("legacy_memory_tokens", estimateTokens(readLongTerm()));
        stats.put("today_notes_tokens", estimateTokens(readToday()));

        if (!entries.isEmpty()) {
            DoubleSummaryStatistics importanceStats = entries.stream()
                    .mapToDouble(MemoryEntry::getImportance)
                    .summaryStatistics();
            stats.put("avg_importance", String.format("%.2f", importanceStats.getAverage()));
            stats.put("max_importance", String.format("%.2f", importanceStats.getMax()));

            DoubleSummaryStatistics scoreStats = entries.stream()
                    .mapToDouble(MemoryEntry::computeScore)
                    .summaryStatistics();
            stats.put("avg_score", String.format("%.3f", scoreStats.getAverage()));
        }

        return stats;
    }

    // ==================== 工具方法 ====================

    private String getTodayFile() {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String monthDir = dateStr.substring(0, 6);
        return scopedNotesDirectory(scopeFor(null)).resolve(monthDir).resolve(dateStr + ".md").toString();
    }

    private void ensureDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            logger.warn("Failed to create directory: {}", path);
        }
    }

    /**
     * 估算字符串的 Token 数（粗略估算：英文 4 字符=1 token，中文 1 字符=1 token）。
     *
     * @param text 文本内容
     * @return 估算的 Token 数
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int englishChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                englishChars++;
            }
        }
        return chineseChars + englishChars / 4;
    }

    /** 内部记录类，用于排序时携带得分 */
    private record ScoredEntry(MemoryEntry entry, double score) {}
}

package io.jobclaw.agent.evolution;

import io.jobclaw.agent.AgentLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆进化引擎，基于反馈和摘要自动优化结构化记忆。
 *
 * 核心功能：
 * - 从会话摘要中快速提取结构化记忆
 * - 基于用户反馈优化记忆重要性
 * - 定期合并相似记忆，消除冗余
 * - 根据时间衰减和访问频率调整记忆优先级
 *
 * 进化策略：
 * 1. 新记忆创建：从摘要、心跳、用户显式指令中提取
 * 2. 重要性调整：根据反馈和使用频率动态调整
 * 3. 相似记忆合并：定期检测并合并语义相近的记忆
 * 4. 低分记忆归档：长期未被访问且评分低的记忆移入归档
 */
public class MemoryEvolver {

    private static final Logger logger = LoggerFactory.getLogger(MemoryEvolver.class);

    private static final String EXTRACTION_INSTRUCTION =
            "Extract key facts and important information from this conversation summary. " +
            "Return each fact as a separate line with format: [TAGS] content\n" +
            "Example: [preference] User prefers Python over Java\n" +
            "Example: [project] Working on JobClaw AI agent framework\n";

    private static final String MERGE_INSTRUCTION =
            "Merge these similar memory entries into one comprehensive entry. " +
            "Preserve all important information while removing redundancy.\n";

    private final MemoryStore memoryStore;
    private final AgentLoop agentLoop;  // 用于调用 LLM 进行提取和合并
    private final String model;

    /**
     * 构造记忆进化引擎。
     *
     * @param memoryStore 记忆存储
     * @param agentLoop   Agent 循环（用于调用 LLM）
     * @param model       使用的模型
     */
    public MemoryEvolver(MemoryStore memoryStore, AgentLoop agentLoop, String model) {
        this.memoryStore = memoryStore;
        this.agentLoop = agentLoop;
        this.model = model;
    }

    /**
     * 从会话摘要中快速提取结构化记忆（轻量级，不调用 LLM）。
     *
     * 使用简单规则提取：
     * - 包含"用户"、"偏好"、"喜欢"等关键词的句子
     * - 包含项目名称、技术栈的句子
     * - 包含日期、时间的提醒事项
     *
     * @param sessionKey 会话键
     * @param summary    会话摘要
     */
    public void quickExtractFromSummary(String sessionKey, String summary) {
        if (summary == null || summary.isEmpty()) {
            return;
        }

        List<String> extractedEntries = extractWithRules(summary);

        for (String entry : extractedEntries) {
            // 检查是否已存在相似记忆
            if (!isDuplicate(entry)) {
                double importance = calculateImportance(entry);
                List<String> tags = extractTags(entry);
                memoryStore.addEntry(entry, importance, tags, "session_summary");
            }
        }

        if (!extractedEntries.isEmpty()) {
            logger.info("Quick extracted {} memory entries from session: {}",
                    extractedEntries.size(), sessionKey);
        }
    }

    /**
     * 使用 LLM 从摘要中提取结构化记忆（完整模式）。
     *
     * @param sessionKey 会话键
     * @param summary    会话摘要
     */
    public void extractFromSummary(String sessionKey, String summary) {
        if (summary == null || summary.isEmpty()) {
            return;
        }

        try {
            String prompt = EXTRACTION_INSTRUCTION + "\n\nSUMMARY:\n" + summary;
            String response = callLLM(prompt);

            if (response != null && !response.isEmpty()) {
                parseAndAddEntries(response, "session_summary");
                logger.info("Extracted memory entries from session: {}", sessionKey);
            }
        } catch (Exception e) {
            logger.warn("LLM extraction failed, falling back to quick extract: {}", e.getMessage());
            quickExtractFromSummary(sessionKey, summary);
        }
    }

    /**
     * 基于用户反馈优化记忆。
     *
     * @param memoryId   记忆 ID
     * @param feedback   反馈类型（positive/negative）
     * @param delta      重要性调整幅度
     */
    public void adjustImportance(String memoryId, String feedback, double delta) {
        MemoryEntry entry = findEntryById(memoryId);
        if (entry == null) {
            logger.warn("Memory entry not found: {}", memoryId);
            return;
        }

        double currentImportance = entry.getImportance();
        double newImportance = "positive".equals(feedback)
                ? Math.min(1.0, currentImportance + delta)
                : Math.max(0.0, currentImportance - delta);

        entry.setImportance(newImportance);
        logger.debug("Adjusted memory importance: id={}, old={}, new={}",
                memoryId, currentImportance, newImportance);
    }

    /**
     * 合并相似记忆。
     *
     * 定期检测语义相近的记忆并合并。
     *
     * @param entries 要检查的记忆列表
     */
    public void mergeSimilarEntries(List<MemoryEntry> entries) {
        if (entries == null || entries.size() < 2) {
            return;
        }

        // 简单的相似性检测：基于标签重叠
        Map<Set<String>, List<MemoryEntry>> groupedByTags = new HashMap<>();

        for (MemoryEntry entry : entries) {
            Set<String> tagKey = normalizeTags(entry.getTags());
            groupedByTags.computeIfAbsent(tagKey, k -> new ArrayList<>()).add(entry);
        }

        // 合并每组相似记忆
        for (Map.Entry<Set<String>, List<MemoryEntry>> group : groupedByTags.entrySet()) {
            if (group.getValue().size() > 1) {
                mergeEntries(group.getValue());
            }
        }
    }

    /**
     * 执行完整的进化周期。
     *
     * 包括：
     * - 清理过期记忆
     * - 合并相似记忆
     * - 归档低分记忆
     */
    public void evolve() {
        List<MemoryEntry> allEntries = memoryStore.getEntries();

        if (allEntries.isEmpty()) {
            return;
        }

        // 1. 识别需要归档的低分记忆
        List<MemoryEntry> toArchive = new ArrayList<>();
        for (MemoryEntry entry : allEntries) {
            if (shouldArchive(entry)) {
                toArchive.add(entry);
            }
        }

        if (!toArchive.isEmpty()) {
            memoryStore.archiveEntries(toArchive);
            logger.info("Archived {} low-score memory entries", toArchive.size());
        }

        // 2. 合并相似记忆（可选，耗时操作）
        // mergeSimilarEntries(allEntries);
    }

    /**
     * 基于反馈进行记忆进化。
     *
     * @param feedback 评估反馈
     */
    public void evolveWithFeedback(EvaluationFeedback feedback) {
        // TODO: 实现基于反馈的进化逻辑
        logger.debug("Processing feedback-based evolution: {}", feedback.getType());
    }

    // ==================== 规则提取方法 ====================

    /**
     * 使用简单规则从摘要中提取记忆条目。
     *
     * @param summary 会话摘要
     * @return 提取的记忆条目列表
     */
    private List<String> extractWithRules(String summary) {
        List<String> entries = new ArrayList<>();

        // 分割成句子
        String[] sentences = summary.split("[.!?。！？]+");

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }

            // 检查是否包含关键词
            if (containsKeyword(sentence)) {
                entries.add(sentence);
            }
        }

        return entries;
    }

    /**
     * 检查句子是否包含记忆相关的关键词。
     *
     * @param sentence 句子
     * @return 包含关键词返回 true
     */
    private boolean containsKeyword(String sentence) {
        String lower = sentence.toLowerCase();

        // 中文关键词
        String[] chineseKeywords = {"用户", "偏好", "喜欢", "习惯", "常用", "项目", "工作", "学习", "计划", "提醒"};
        for (String kw : chineseKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }

        // 英文关键词
        String[] englishKeywords = {"user", "prefer", "like", "habit", "project", "work", "study", "plan", "remember"};
        for (String kw : englishKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 从条目中提取标签。
     *
     * @param entry 记忆条目
     * @return 标签列表
     */
    private List<String> extractTags(String entry) {
        List<String> tags = new ArrayList<>();

        // 简单的标签提取规则
        if (entry.toLowerCase().contains("prefer") || entry.contains("偏好") || entry.contains("喜欢")) {
            tags.add("preference");
        }
        if (entry.toLowerCase().contains("project") || entry.contains("项目") || entry.contains("工作")) {
            tags.add("project");
        }
        if (entry.toLowerCase().contains("user") || entry.contains("用户")) {
            tags.add("user");
        }
        if (entry.toLowerCase().contains("reminder") || entry.contains("提醒") || entry.contains("计划")) {
            tags.add("reminder");
        }

        return tags;
    }

    /**
     * 计算记忆条目的重要性评分。
     *
     * @param entry 记忆条目
     * @return 重要性评分 (0.0 ~ 1.0)
     */
    private double calculateImportance(String entry) {
        double baseScore = 0.5;

        // 包含用户偏好信息的更重要
        if (entry.contains("偏好") || entry.contains("prefer") ||
            entry.contains("喜欢") || entry.contains("like")) {
            baseScore += 0.2;
        }

        // 包含项目信息的更重要
        if (entry.contains("项目") || entry.contains("project") ||
            entry.contains("工作") || entry.contains("work")) {
            baseScore += 0.15;
        }

        // 包含提醒事项的重要
        if (entry.contains("提醒") || entry.contains("remember") ||
            entry.contains("计划") || entry.contains("plan")) {
            baseScore += 0.1;
        }

        return Math.min(1.0, baseScore);
    }

    /**
     * 检查是否已存在相似的记忆。
     *
     * @param newEntry 新的记忆条目
     * @return 存在相似记忆返回 true
     */
    private boolean isDuplicate(String newEntry) {
        List<MemoryEntry> existing = memoryStore.getEntries();
        String normalizedNew = normalizeText(newEntry);

        for (MemoryEntry entry : existing) {
            String normalizedExisting = normalizeText(entry.getContent());

            // 简单的重复检测：包含关系或相似度很高
            if (normalizedExisting.contains(normalizedNew) ||
                normalizedNew.contains(normalizedExisting)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 解析 LLM 返回的提取结果并添加到记忆存储。
     *
     * @param response LLM 响应
     * @param source   来源标识
     */
    private void parseAndAddEntries(String response, String source) {
        String[] lines = response.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 解析格式：[TAGS] content
            List<String> tags = new ArrayList<>();
            String content = line;

            Pattern pattern = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.+)$");
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String tagStr = matcher.group(1);
                tags = Arrays.asList(tagStr.split("\\s*,\\s*"));
                content = matcher.group(2);
            }

            double importance = calculateImportance(content);
            memoryStore.addEntry(content, importance, tags, source);
        }
    }

    /**
     * 查找指定 ID 的记忆条目。
     *
     * @param memoryId 记忆 ID
     * @return 找到的条目，未找到返回 null
     */
    private MemoryEntry findEntryById(String memoryId) {
        for (MemoryEntry entry : memoryStore.getEntries()) {
            if (entry.getId().equals(memoryId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 合并多个相似记忆。
     *
     * @param entries 要合并的记忆列表
     */
    private void mergeEntries(List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }

        // 使用 LLM 合并
        StringBuilder contents = new StringBuilder();
        for (MemoryEntry entry : entries) {
            contents.append("- ").append(entry.getContent()).append("\n");
        }

        String prompt = MERGE_INSTRUCTION + "\nENTRIES:\n" + contents;

        try {
            String mergedContent = callLLM(prompt);
            if (mergedContent != null && !mergedContent.isEmpty()) {
                // 计算平均重要性
                double avgImportance = entries.stream()
                        .mapToDouble(MemoryEntry::getImportance)
                        .average()
                        .orElse(0.5);

                // 合并所有标签
                Set<String> allTags = new HashSet<>();
                for (MemoryEntry entry : entries) {
                    if (entry.getTags() != null) {
                        allTags.addAll(entry.getTags());
                    }
                }

                // 添加新记忆
                memoryStore.addEntry(mergedContent, avgImportance, new ArrayList<>(allTags), "evolution");

                // 移除旧记忆
                for (MemoryEntry entry : entries) {
                    memoryStore.removeEntry(entry.getId());
                }

                logger.info("Merged {} memory entries into one", entries.size());
            }
        } catch (Exception e) {
            logger.warn("Failed to merge entries with LLM: {}", e.getMessage());
        }
    }

    /**
     * 判断记忆是否应该被归档。
     *
     * @param entry 记忆条目
     * @return 应该归档返回 true
     */
    private boolean shouldArchive(MemoryEntry entry) {
        // 重要性低且长时间未被访问的记忆
        return entry.getImportance() < 0.2 && entry.getAccessCount() == 0;
    }

    /**
     * 标准化标签集合（用于分组）。
     *
     * @param tags 标签列表
     * @return 标准化的标签集合
     */
    private Set<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String tag : tags) {
            if (tag != null) {
                normalized.add(tag.toLowerCase().trim());
            }
        }
        return normalized;
    }

    /**
     * 标准化文本（用于重复检测）。
     *
     * @param text 文本
     * @return 标准化后的文本
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase()
                .replaceAll("[\\s,，。！？!?;；:：]+", " ")
                .trim();
    }

    /**
     * 调用 LLM 生成响应。
     *
     * @param prompt 提示词
     * @return LLM 响应
     */
    private String callLLM(String prompt) {
        Map<String, Object> options = new HashMap<>();
        options.put("max_tokens", 512);
        options.put("temperature", 0.3);

        try {
            return agentLoop.callLLM(prompt, options);
        } catch (Exception e) {
            logger.error("LLM call failed: {}", e.getMessage());
            return null;
        }
    }
}

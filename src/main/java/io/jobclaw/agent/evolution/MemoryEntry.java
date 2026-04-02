package io.jobclaw.agent.evolution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 结构化记忆条目，带有元数据支持智能排序和进化。
 *
 * 每条记忆包含：
 * - 内容：记忆的实际文本
 * - 重要性：0.0-1.0 的评分，决定记忆优先级
 * - 标签：用于分类和检索的关键词
 * - 来源：记忆创建来源（session_summary, heartbeat, user_explicit, evolution）
 * - 时间戳：创建和最后访问时间
 * - 访问计数：被检索到的次数，用于重要性衰减计算
 *
 * 综合得分计算：
 * score = importance × decayFactor × accessFactor
 * - decayFactor: 时间衰减，越近的记忆得分越高
 * - accessFactor: 访问频率，常被访问的记忆得分越高
 */
public class MemoryEntry {

    /** 记忆唯一标识 */
    private String id;

    /** 记忆内容 */
    private String content;

    /** 重要性评分 (0.0 ~ 1.0) */
    private double importance;

    /** 标签列表 */
    private List<String> tags;

    /** 来源标识 */
    private String source;

    /** 创建时间 */
    private Instant createdAt;

    /** 最后访问时间 */
    private Instant lastAccessedAt;

    /** 访问计数 */
    private int accessCount;

    /**
     * 默认构造函数（Jackson 反序列化需要）
     */
    public MemoryEntry() {
    }

    /**
     * 创建记忆条目。
     *
     * @param content    记忆内容
     * @param importance 重要性评分 (0.0 ~ 1.0)
     * @param tags       标签列表
     * @param source     来源标识
     */
    public MemoryEntry(String content, double importance, List<String> tags, String source) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.importance = Math.max(0.0, Math.min(1.0, importance));
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.source = source;
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
        this.accessCount = 0;
    }

    // ==================== Getter/Setter ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = Math.max(0.0, Math.min(1.0, importance));
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @JsonProperty("lastAccessedAt")
    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    @JsonProperty("lastAccessedAt")
    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    @JsonProperty("accessCount")
    public int getAccessCount() {
        return accessCount;
    }

    @JsonProperty("accessCount")
    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }

    // ==================== 核心方法 ====================

    /**
     * 记录一次访问，更新访问时间和计数。
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessedAt = Instant.now();
    }

    /**
     * 计算综合得分，用于记忆排序。
     *
     * 综合考虑：
     * - 重要性评分（基础分）
     * - 时间衰减（越近越重要）
     * - 访问频率（常被访问的更重要）
     *
     * @return 综合得分
     */
    @JsonIgnore
    public double computeScore() {
        double decayFactor = computeTimeDecay();
        double accessFactor = 1.0 + (Math.log(accessCount + 1) / Math.log(10));
        return importance * decayFactor * accessFactor;
    }

    /**
     * 计算时间衰减因子。
     *
     * 使用半衰期策略：
     * - 1 天内：1.0
     * - 7 天内：0.8
     * - 30 天内：0.5
     * - 90 天内：0.3
     * - 超过 90 天：0.1
     *
     * @return 时间衰减因子 (0.1 ~ 1.0)
     */
    @JsonIgnore
    public double computeTimeDecay() {
        Duration age = Duration.between(createdAt, Instant.now());
        long hours = age.toHours();

        if (hours < 24) {
            return 1.0;
        } else if (hours < 168) { // 7 天
            return 0.8;
        } else if (hours < 720) { // 30 天
            return 0.5;
        } else if (hours < 2160) { // 90 天
            return 0.3;
        } else {
            return 0.1;
        }
    }

    /**
     * 计算与关键词的相关性得分。
     *
     * @param keywords 关键词列表
     * @return 匹配的关键词数量
     */
    @JsonIgnore
    public int computeRelevance(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }

        int matchCount = 0;
        String lowerContent = content.toLowerCase();

        for (String keyword : keywords) {
            if (lowerContent.contains(keyword.toLowerCase())) {
                matchCount++;
            }
        }

        return matchCount;
    }

    /**
     * 添加标签。
     *
     * @param tag 要添加的标签
     */
    public void addTag(String tag) {
        if (this.tags == null) {
            this.tags = new ArrayList<>();
        }
        if (!this.tags.contains(tag)) {
            this.tags.add(tag);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemoryEntry that = (MemoryEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MemoryEntry{" +
                "id='" + id + '\'' +
                ", content='" + truncate(content, 50) + '\'' +
                ", importance=" + importance +
                ", tags=" + tags +
                ", source='" + source + '\'' +
                ", accessCount=" + accessCount +
                '}';
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}

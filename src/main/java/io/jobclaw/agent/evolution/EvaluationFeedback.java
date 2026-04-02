package io.jobclaw.agent.evolution;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 评估反馈，用于记录用户对 Agent 响应的反馈以驱动进化。
 *
 * 反馈类型：
 * - positive: 正面反馈，响应有帮助
 * - negative: 负面反馈，响应不理想
 * - neutral: 中性反馈
 *
 * 反馈来源：
 * - explicit: 用户显式反馈（如点赞/点踩）
 * - implicit: 隐式反馈（如用户重新提问、忽略响应）
 * - tool_result: 工具执行结果反馈
 */
public class EvaluationFeedback {

    public enum FeedbackType {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }

    public enum FeedbackSource {
        EXPLICIT,
        IMPLICIT,
        TOOL_RESULT
    }

    /** 反馈唯一标识 */
    private String id;

    /** 反馈类型 */
    private FeedbackType type;

    /** 反馈来源 */
    private FeedbackSource source;

    /** 关联的会话键 */
    private String sessionKey;

    /** 关联的消息内容（可选） */
    private String messageContent;

    /** 反馈详情（可选） */
    private String details;

    /** 创建时间 */
    private Instant createdAt;

    /** 元数据（可选） */
    private Map<String, String> metadata;

    /**
     * 默认构造函数（Jackson 反序列化需要）
     */
    public EvaluationFeedback() {
    }

    /**
     * 创建评估反馈。
     *
     * @param type     反馈类型
     * @param source   反馈来源
     * @param sessionKey 关联的会话键
     */
    public EvaluationFeedback(FeedbackType type, FeedbackSource source, String sessionKey) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.source = source;
        this.sessionKey = sessionKey;
        this.createdAt = Instant.now();
        this.metadata = new HashMap<>();
    }

    /**
     * 创建简单的正面反馈。
     *
     * @param sessionKey 会话键
     * @return 正面反馈实例
     */
    public static EvaluationFeedback positive(String sessionKey) {
        return new EvaluationFeedback(FeedbackType.POSITIVE, FeedbackSource.IMPLICIT, sessionKey);
    }

    /**
     * 创建简单的负面反馈。
     *
     * @param sessionKey 会话键
     * @return 负面反馈实例
     */
    public static EvaluationFeedback negative(String sessionKey) {
        return new EvaluationFeedback(FeedbackType.NEGATIVE, FeedbackSource.IMPLICIT, sessionKey);
    }

    // ==================== Getter/Setter ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public FeedbackType getType() {
        return type;
    }

    public void setType(FeedbackType type) {
        this.type = type;
    }

    public FeedbackSource getSource() {
        return source;
    }

    public void setSource(FeedbackSource source) {
        this.source = source;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    /**
     * 添加元数据。
     *
     * @param key   键
     * @param value 值
     */
    public void addMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "EvaluationFeedback{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", source=" + source +
                ", sessionKey='" + sessionKey + '\'' +
                '}';
    }
}

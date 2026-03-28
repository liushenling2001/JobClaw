package io.jobclaw.agent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行过程事件 - 用于跟踪 Agent 执行过程中的每一步
 */
public class ExecutionEvent {

    public enum EventType {
        /** Agent 开始思考 */
        THINK_START,
        /** Agent 思考中（流式输出） */
        THINK_STREAM,
        /** Agent 思考结束 */
        THINK_END,
        /** 工具调用开始 */
        TOOL_START,
        /** 工具调用结束 */
        TOOL_END,
        /** 工具输出 */
        TOOL_OUTPUT,
        /** 工具执行错误 */
        TOOL_ERROR,
        /** 错误事件 */
        ERROR,
        /** 最终响应 */
        FINAL_RESPONSE,
        /** 自定义消息 */
        CUSTOM
    }

    private final String sessionId;
    private final EventType type;
    private final String content;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    public ExecutionEvent(String sessionId, EventType type, String content) {
        this.sessionId = sessionId;
        this.type = type;
        this.content = content != null ? content : "";
        this.timestamp = Instant.now();
        this.metadata = new HashMap<>();
    }

    public ExecutionEvent(String sessionId, EventType type, String content, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.type = type;
        this.content = content != null ? content : "";
        this.timestamp = Instant.now();
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    public String getSessionId() { return sessionId; }
    public EventType getType() { return type; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, Object> getMetadata() { return metadata; }

    /**
     * 添加元数据
     */
    public ExecutionEvent withMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    /**
     * 转换为 SSE 数据格式
     */
    public Map<String, Object> toSseData() {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("type", type.name());
        data.put("content", content);
        data.put("timestamp", timestamp.toString());
        data.put("metadata", metadata);
        return data;
    }

    @Override
    public String toString() {
        return "ExecutionEvent{" +
                "sessionId='" + sessionId + '\'' +
                ", type=" + type +
                ", contentLength=" + content.length() +
                ", timestamp=" + timestamp +
                '}';
    }
}

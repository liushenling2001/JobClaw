package io.jobclaw.agent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Execution event emitted during agent processing and streamed to SSE consumers.
 */
public class ExecutionEvent {

    public enum EventType {
        THINK_START,
        THINK_STREAM,
        THINK_END,
        TOOL_START,
        TOOL_END,
        TOOL_OUTPUT,
        TOOL_ERROR,
        MODEL_METRICS,
        ERROR,
        FINAL_RESPONSE,
        CUSTOM
    }

    private final String sessionId;
    private final String runId;
    private final String parentRunId;
    private final String agentId;
    private final String agentName;
    private final EventType type;
    private final String content;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    public ExecutionEvent(String sessionId, EventType type, String content) {
        this(sessionId, type, content, null);
    }

    public ExecutionEvent(String sessionId, EventType type, String content, Map<String, Object> metadata) {
        this(
                sessionId,
                type,
                content,
                metadata,
                AgentExecutionContext.getCurrentRunId(),
                AgentExecutionContext.getCurrentParentRunId(),
                currentAgentId(),
                currentAgentName(),
                Instant.now()
        );
    }

    public ExecutionEvent(String sessionId,
                          EventType type,
                          String content,
                          Map<String, Object> metadata,
                          String runId,
                          String parentRunId,
                          String agentId,
                          String agentName) {
        this(sessionId, type, content, metadata, runId, parentRunId, agentId, agentName, Instant.now());
    }

    private ExecutionEvent(String sessionId,
                           EventType type,
                           String content,
                           Map<String, Object> metadata,
                           String runId,
                           String parentRunId,
                           String agentId,
                           String agentName,
                           Instant timestamp) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.type = type;
        this.content = content != null ? content : "";
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public EventType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public ExecutionEvent withMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public ExecutionEvent routedTo(String targetSessionId,
                                   String runId,
                                   String parentRunId,
                                   String agentId,
                                   String agentName) {
        return new ExecutionEvent(
                targetSessionId,
                type,
                content,
                metadata,
                runId,
                parentRunId,
                agentId,
                agentName,
                timestamp
        );
    }

    public Map<String, Object> toSseData() {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("runId", runId);
        data.put("parentRunId", parentRunId);
        data.put("agentId", agentId);
        data.put("agentName", agentName);
        data.put("type", type.name());
        data.put("content", content);
        data.put("timestamp", timestamp.toString());
        data.put("metadata", metadata);
        return data;
    }

    @SuppressWarnings("unchecked")
    public static ExecutionEvent fromSseData(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object typeValue = data.get("type");
        EventType eventType;
        try {
            eventType = EventType.valueOf(String.valueOf(typeValue));
        } catch (Exception e) {
            return null;
        }
        Instant eventTimestamp = Instant.now();
        Object timestampValue = data.get("timestamp");
        if (timestampValue != null) {
            try {
                eventTimestamp = Instant.parse(String.valueOf(timestampValue));
            } catch (Exception ignored) {
                eventTimestamp = Instant.now();
            }
        }
        Object metadataValue = data.get("metadata");
        Map<String, Object> eventMetadata = metadataValue instanceof Map<?, ?> raw
                ? new HashMap<>((Map<String, Object>) raw)
                : new HashMap<>();
        return new ExecutionEvent(
                stringValue(data.get("sessionId")),
                eventType,
                stringValue(data.get("content")),
                eventMetadata,
                stringValue(data.get("runId")),
                stringValue(data.get("parentRunId")),
                stringValue(data.get("agentId")),
                stringValue(data.get("agentName")),
                eventTimestamp
        );
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    @Override
    public String toString() {
        return "ExecutionEvent{" +
                "sessionId='" + sessionId + '\'' +
                ", runId='" + runId + '\'' +
                ", agentId='" + agentId + '\'' +
                ", type=" + type +
                ", contentLength=" + content.length() +
                ", timestamp=" + timestamp +
                '}';
    }

    private static String currentAgentId() {
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        return scope != null ? scope.agentId() : null;
    }

    private static String currentAgentName() {
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        return scope != null ? scope.agentName() : null;
    }
}

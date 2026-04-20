package io.jobclaw.context.result;

import java.time.Instant;

public class ContextRef {
    private String refId;
    private String sessionKey;
    private String runId;
    private String sourceType;
    private String sourceName;
    private Instant createdAt;
    private int contentLength;
    private String preview;

    public ContextRef() {
    }

    public ContextRef(String refId, String sessionKey, String runId, String sourceType, String sourceName,
                      Instant createdAt, int contentLength, String preview) {
        this.refId = refId;
        this.sessionKey = sessionKey;
        this.runId = runId;
        this.sourceType = sourceType;
        this.sourceName = sourceName;
        this.createdAt = createdAt;
        this.contentLength = contentLength;
        this.preview = preview;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public int getContentLength() {
        return contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }
}

package io.jobclaw.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SessionRecord {

    private String sessionId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastMessageAt;
    private long messageCount;
    private String status;
    private List<String> tags;

    public SessionRecord() {
        this.tags = new ArrayList<>();
    }

    public SessionRecord(String sessionId, String title, Instant createdAt, Instant updatedAt,
                         Instant lastMessageAt, long messageCount, String status, List<String> tags) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastMessageAt = lastMessageAt;
        this.messageCount = messageCount;
        this.status = status;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public long getMessageCount() { return messageCount; }
    public void setMessageCount(long messageCount) { this.messageCount = messageCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public SessionRecord withUpdatedAt(Instant updatedAt) {
        return new SessionRecord(sessionId, title, createdAt, updatedAt, lastMessageAt, messageCount, status, tags);
    }

    public SessionRecord withLastMessageAt(Instant lastMessageAt) {
        return new SessionRecord(sessionId, title, createdAt, updatedAt, lastMessageAt, messageCount, status, tags);
    }

    public SessionRecord withMessageCount(long messageCount) {
        return new SessionRecord(sessionId, title, createdAt, updatedAt, lastMessageAt, messageCount, status, tags);
    }
}

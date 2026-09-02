package io.jobclaw.workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WorkspaceRecord {

    private String id;
    private String title;
    private String path;
    private List<String> sessionKeys = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public WorkspaceRecord() {
    }

    public WorkspaceRecord(String id, String title, String path, List<String> sessionKeys,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.path = path;
        this.sessionKeys = sessionKeys != null ? new ArrayList<>(sessionKeys) : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<String> getSessionKeys() { return sessionKeys; }
    public void setSessionKeys(List<String> sessionKeys) {
        this.sessionKeys = sessionKeys != null ? new ArrayList<>(sessionKeys) : new ArrayList<>();
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

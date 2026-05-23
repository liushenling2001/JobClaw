package io.jobclaw.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RunRecord {
    private String runId;
    private String sessionKey;
    private String parentRunId;
    private String resumedFromRunId;
    private RunStatus status;
    private String task;
    private String source;
    private String stateRoot;
    private String projectRoot;
    private String cwd;
    private String gitRoot;
    private String gitBranch;
    private boolean dirty;
    private Instant createdAt;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant heartbeatAt;
    private Instant completedAt;
    private Integer exitCode;
    private String finalResponse;
    private String error;
    private String model;
    private String agentId;
    private String approvalMode;
    private String sandboxMode;
    private List<String> manifestIds = new ArrayList<>();
    private List<String> artifactPaths = new ArrayList<>();
    private List<String> contextRefIds = new ArrayList<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    public String getResumedFromRunId() {
        return resumedFromRunId;
    }

    public void setResumedFromRunId(String resumedFromRunId) {
        this.resumedFromRunId = resumedFromRunId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStateRoot() {
        return stateRoot;
    }

    public void setStateRoot(String stateRoot) {
        this.stateRoot = stateRoot;
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getGitRoot() {
        return gitRoot;
    }

    public void setGitRoot(String gitRoot) {
        this.gitRoot = gitRoot;
    }

    public String getGitBranch() {
        return gitBranch;
    }

    public void setGitBranch(String gitBranch) {
        this.gitBranch = gitBranch;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(String finalResponse) {
        this.finalResponse = finalResponse;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getApprovalMode() {
        return approvalMode;
    }

    public void setApprovalMode(String approvalMode) {
        this.approvalMode = approvalMode;
    }

    public String getSandboxMode() {
        return sandboxMode;
    }

    public void setSandboxMode(String sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    public List<String> getManifestIds() {
        return manifestIds;
    }

    public void setManifestIds(List<String> manifestIds) {
        this.manifestIds = manifestIds != null ? new ArrayList<>(manifestIds) : new ArrayList<>();
    }

    public List<String> getArtifactPaths() {
        return artifactPaths;
    }

    public void setArtifactPaths(List<String> artifactPaths) {
        this.artifactPaths = artifactPaths != null ? new ArrayList<>(artifactPaths) : new ArrayList<>();
    }

    public List<String> getContextRefIds() {
        return contextRefIds;
    }

    public void setContextRefIds(List<String> contextRefIds) {
        this.contextRefIds = contextRefIds != null ? new ArrayList<>(contextRefIds) : new ArrayList<>();
    }
}

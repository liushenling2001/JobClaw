package io.jobclaw.agent.learning;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.planning.TaskPlanningMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LearningCandidate {

    private String id;
    private LearningCandidateType type;
    private LearningCandidateStatus status = LearningCandidateStatus.PENDING;
    private String title;
    private String reason;
    private String sessionId;
    private String sourceRunId;
    private String taskInput;
    private TaskPlanningMode planningMode;
    private DeliveryType deliveryType;
    private String proposal;
    private List<String> tags = new ArrayList<>();
    private double confidence;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public LearningCandidateType getType() { return type; }
    public void setType(LearningCandidateType type) { this.type = type; }

    public LearningCandidateStatus getStatus() { return status; }
    public void setStatus(LearningCandidateStatus status) { this.status = status != null ? status : LearningCandidateStatus.PENDING; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(String sourceRunId) { this.sourceRunId = sourceRunId; }

    public String getTaskInput() { return taskInput; }
    public void setTaskInput(String taskInput) { this.taskInput = taskInput; }

    public TaskPlanningMode getPlanningMode() { return planningMode; }
    public void setPlanningMode(TaskPlanningMode planningMode) { this.planningMode = planningMode; }

    public DeliveryType getDeliveryType() { return deliveryType; }
    public void setDeliveryType(DeliveryType deliveryType) { this.deliveryType = deliveryType; }

    public String getProposal() { return proposal; }
    public void setProposal(String proposal) { this.proposal = proposal; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>(); }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = Math.max(0.0, Math.min(1.0, confidence)); }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }
}

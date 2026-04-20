package io.jobclaw.agent.workflow;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.planning.TaskPlanningMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WorkflowRecipe {

    private String id;
    private String name;
    private String taskSignature;
    private String applicability;
    private TaskPlanningMode planningMode;
    private DeliveryType deliveryType;
    private List<String> toolSequence = new ArrayList<>();
    private List<String> requiredTools = new ArrayList<>();
    private String subtaskPattern;
    private int successCount;
    private double confidence;
    private List<String> sourceRunIds = new ArrayList<>();
    private Instant createdAt;
    private Instant lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTaskSignature() { return taskSignature; }
    public void setTaskSignature(String taskSignature) { this.taskSignature = taskSignature; }

    public String getApplicability() { return applicability; }
    public void setApplicability(String applicability) { this.applicability = applicability; }

    public TaskPlanningMode getPlanningMode() { return planningMode; }
    public void setPlanningMode(TaskPlanningMode planningMode) { this.planningMode = planningMode; }

    public DeliveryType getDeliveryType() { return deliveryType; }
    public void setDeliveryType(DeliveryType deliveryType) { this.deliveryType = deliveryType; }

    public List<String> getToolSequence() { return toolSequence; }
    public void setToolSequence(List<String> toolSequence) { this.toolSequence = toolSequence != null ? new ArrayList<>(toolSequence) : new ArrayList<>(); }

    public List<String> getRequiredTools() { return requiredTools; }
    public void setRequiredTools(List<String> requiredTools) { this.requiredTools = requiredTools != null ? new ArrayList<>(requiredTools) : new ArrayList<>(); }

    public String getSubtaskPattern() { return subtaskPattern; }
    public void setSubtaskPattern(String subtaskPattern) { this.subtaskPattern = subtaskPattern; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = Math.max(0.0, Math.min(1.0, confidence)); }

    public List<String> getSourceRunIds() { return sourceRunIds; }
    public void setSourceRunIds(List<String> sourceRunIds) { this.sourceRunIds = sourceRunIds != null ? new ArrayList<>(sourceRunIds) : new ArrayList<>(); }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}

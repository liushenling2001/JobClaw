package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.planning.TaskPlanningMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExperienceMemory {

    private String id;
    private String sourceCandidateId;
    private ExperienceMemoryType type;
    private ExperienceMemoryStatus status = ExperienceMemoryStatus.ACTIVE;
    private String title;
    private String applicability;
    private TaskPlanningMode planningMode;
    private DeliveryType deliveryType;
    private List<String> toolSequence = new ArrayList<>();
    private List<String> avoidRules = new ArrayList<>();
    private String outputFormat;
    private String proposal;
    private double confidence;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceCandidateId() { return sourceCandidateId; }
    public void setSourceCandidateId(String sourceCandidateId) { this.sourceCandidateId = sourceCandidateId; }

    public ExperienceMemoryType getType() { return type; }
    public void setType(ExperienceMemoryType type) { this.type = type; }

    public ExperienceMemoryStatus getStatus() { return status; }
    public void setStatus(ExperienceMemoryStatus status) { this.status = status != null ? status : ExperienceMemoryStatus.ACTIVE; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getApplicability() { return applicability; }
    public void setApplicability(String applicability) { this.applicability = applicability; }

    public TaskPlanningMode getPlanningMode() { return planningMode; }
    public void setPlanningMode(TaskPlanningMode planningMode) { this.planningMode = planningMode; }

    public DeliveryType getDeliveryType() { return deliveryType; }
    public void setDeliveryType(DeliveryType deliveryType) { this.deliveryType = deliveryType; }

    public List<String> getToolSequence() { return toolSequence; }
    public void setToolSequence(List<String> toolSequence) { this.toolSequence = toolSequence != null ? new ArrayList<>(toolSequence) : new ArrayList<>(); }

    public List<String> getAvoidRules() { return avoidRules; }
    public void setAvoidRules(List<String> avoidRules) { this.avoidRules = avoidRules != null ? new ArrayList<>(avoidRules) : new ArrayList<>(); }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    public String getProposal() { return proposal; }
    public void setProposal(String proposal) { this.proposal = proposal; }

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

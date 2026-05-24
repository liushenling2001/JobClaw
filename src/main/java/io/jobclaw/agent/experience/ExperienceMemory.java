package io.jobclaw.agent.experience;

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
    private ExperienceMemoryUserState userState = ExperienceMemoryUserState.AUTO;
    private String title;
    private String taskPattern;
    private String applicability;
    private String methodGuidance;
    private List<String> toolSequence = new ArrayList<>();
    private List<String> avoidRules = new ArrayList<>();
    private String avoidGuidance;
    private String outputFormat;
    private String proposal;
    private String riskLevel = "medium";
    private double confidence;
    private int hitCount;
    private int contradictionCount;
    private Instant lastHitAt;
    private Instant lastContradictedAt;
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

    public ExperienceMemoryUserState getUserState() { return userState; }
    public void setUserState(ExperienceMemoryUserState userState) { this.userState = userState != null ? userState : ExperienceMemoryUserState.AUTO; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTaskPattern() { return taskPattern; }
    public void setTaskPattern(String taskPattern) { this.taskPattern = taskPattern; }

    public String getApplicability() { return applicability; }
    public void setApplicability(String applicability) { this.applicability = applicability; }

    public String getMethodGuidance() { return methodGuidance; }
    public void setMethodGuidance(String methodGuidance) { this.methodGuidance = methodGuidance; }

    public List<String> getToolSequence() { return toolSequence; }
    public void setToolSequence(List<String> toolSequence) { this.toolSequence = toolSequence != null ? new ArrayList<>(toolSequence) : new ArrayList<>(); }

    public List<String> getAvoidRules() { return avoidRules; }
    public void setAvoidRules(List<String> avoidRules) { this.avoidRules = avoidRules != null ? new ArrayList<>(avoidRules) : new ArrayList<>(); }

    public String getAvoidGuidance() { return avoidGuidance; }
    public void setAvoidGuidance(String avoidGuidance) { this.avoidGuidance = avoidGuidance; }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    public String getProposal() { return proposal; }
    public void setProposal(String proposal) { this.proposal = proposal; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel != null && !riskLevel.isBlank() ? riskLevel : "medium"; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = Math.max(0.0, Math.min(1.0, confidence)); }

    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = Math.max(0, hitCount); }

    public int getContradictionCount() { return contradictionCount; }
    public void setContradictionCount(int contradictionCount) { this.contradictionCount = Math.max(0, contradictionCount); }

    public Instant getLastHitAt() { return lastHitAt; }
    public void setLastHitAt(Instant lastHitAt) { this.lastHitAt = lastHitAt; }

    public Instant getLastContradictedAt() { return lastContradictedAt; }
    public void setLastContradictedAt(Instant lastContradictedAt) { this.lastContradictedAt = lastContradictedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }
}

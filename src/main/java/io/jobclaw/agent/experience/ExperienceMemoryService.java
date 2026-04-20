package io.jobclaw.agent.experience;

import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class ExperienceMemoryService {

    private final ExperienceMemoryStore store;

    public ExperienceMemoryService(ExperienceMemoryStore store) {
        this.store = store;
    }

    public Optional<ExperienceMemory> applyAcceptedCandidate(LearningCandidate candidate) {
        if (candidate == null || candidate.getType() == null) {
            return Optional.empty();
        }
        ExperienceMemoryType memoryType = mapType(candidate.getType());
        if (memoryType == null) {
            return Optional.empty();
        }
        List<ExperienceMemory> memories = new ArrayList<>(store.list());
        Optional<ExperienceMemory> existing = memories.stream()
                .filter(memory -> candidate.getId() != null && candidate.getId().equals(memory.getSourceCandidateId()))
                .findFirst();
        ExperienceMemory memory = existing.orElseGet(() -> newMemory(candidate, memoryType));
        memory.setStatus(ExperienceMemoryStatus.ACTIVE);
        memory.setUpdatedAt(Instant.now());
        memory.setConfidence(Math.max(memory.getConfidence(), candidate.getConfidence()));
        if (existing.isEmpty()) {
            memories.add(memory);
        }
        store.saveAll(memories);
        return Optional.of(memory);
    }

    public List<ExperienceMemory> listActive() {
        return store.list().stream()
                .filter(memory -> memory.getStatus() == ExperienceMemoryStatus.ACTIVE)
                .toList();
    }

    private ExperienceMemory newMemory(LearningCandidate candidate, ExperienceMemoryType type) {
        Instant now = Instant.now();
        ExperienceMemory memory = new ExperienceMemory();
        memory.setId(UUID.randomUUID().toString());
        memory.setSourceCandidateId(candidate.getId());
        memory.setType(type);
        memory.setStatus(ExperienceMemoryStatus.ACTIVE);
        memory.setTitle(candidate.getTitle());
        memory.setApplicability(candidate.getTaskInput());
        memory.setPlanningMode(candidate.getPlanningMode());
        memory.setDeliveryType(candidate.getDeliveryType());
        memory.setToolSequence(extractToolSequence(candidate));
        memory.setAvoidRules(type == ExperienceMemoryType.AVOID_RULE
                ? List.of(firstNonBlank(metadataValue(candidate, "failureReason"), candidate.getReason(), candidate.getProposal()))
                : List.of());
        memory.setProposal(candidate.getProposal());
        memory.setConfidence(candidate.getConfidence());
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memory.setMetadata(candidate.getMetadata());
        return memory;
    }

    private ExperienceMemoryType mapType(LearningCandidateType type) {
        return switch (type) {
            case NEGATIVE_LESSON -> ExperienceMemoryType.AVOID_RULE;
            case WORKFLOW, SKILL_UPDATE -> ExperienceMemoryType.WORKFLOW_EXPERIENCE;
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> extractToolSequence(LearningCandidate candidate) {
        Object tools = candidate.getMetadata().get("toolSequence");
        if (tools instanceof List<?>) {
            return ((List<?>) tools).stream()
                    .map(Object::toString)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        if (tools != null && !tools.toString().isBlank()) {
            return List.of(tools.toString());
        }
        return List.of();
    }

    private String metadataValue(LearningCandidate candidate, String key) {
        Map<String, Object> metadata = candidate.getMetadata();
        Object value = metadata != null ? metadata.get(key) : null;
        return value != null ? value.toString() : "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

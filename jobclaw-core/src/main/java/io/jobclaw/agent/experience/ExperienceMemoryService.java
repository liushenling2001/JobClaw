package io.jobclaw.agent.experience;

import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class ExperienceMemoryService {

    private static final Pattern CORRECTION_PATTERN = Pattern.compile(
            "(?i)(不是|不对|错了|不要这样|不要按|旧经验|旧路径|历史路径|又按|误导|wrong|incorrect|not this|do not reuse|old path)"
    );
    private static final double CONTRADICTION_CONFIDENCE_PENALTY = 0.2;

    private final ExperienceMemoryStore store;
    private final Map<String, List<String>> lastInjectedBySession = new ConcurrentHashMap<>();

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
                .filter(memory -> memory.getUserState() != ExperienceMemoryUserState.FORGOTTEN)
                .toList();
    }

    public List<ExperienceMemory> listAll() {
        return store.list();
    }

    public Optional<ExperienceMemory> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.list().stream()
                .filter(memory -> id.equals(memory.getId()))
                .findFirst();
    }

    public List<ExperienceMemory> recordInjected(String sessionId, Collection<String> memoryIds) {
        if (sessionId == null || sessionId.isBlank() || memoryIds == null || memoryIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = memoryIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        lastInjectedBySession.put(sessionId, ids);
        Instant now = Instant.now();
        List<ExperienceMemory> memories = new ArrayList<>(store.list());
        List<ExperienceMemory> updated = new ArrayList<>();
        for (ExperienceMemory memory : memories) {
            if (ids.contains(memory.getId())) {
                memory.setHitCount(memory.getHitCount() + 1);
                memory.setLastHitAt(now);
                memory.setUpdatedAt(now);
                updated.add(memory);
            }
        }
        store.saveAll(memories);
        return updated;
    }

    public List<ExperienceMemory> markLastInjectedContradictedIfCorrection(String sessionId, String userInput) {
        if (sessionId == null || sessionId.isBlank() || userInput == null || userInput.isBlank()
                || !CORRECTION_PATTERN.matcher(userInput).find()) {
            return List.of();
        }
        List<String> ids = lastInjectedBySession.get(sessionId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return markContradicted(ids);
    }

    public List<ExperienceMemory> markContradicted(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<String> normalizedIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        List<ExperienceMemory> memories = new ArrayList<>(store.list());
        List<ExperienceMemory> updated = new ArrayList<>();
        for (ExperienceMemory memory : memories) {
            if (normalizedIds.contains(memory.getId()) && memory.getUserState() != ExperienceMemoryUserState.PINNED) {
                memory.setContradictionCount(memory.getContradictionCount() + 1);
                memory.setLastContradictedAt(now);
                memory.setConfidence(memory.getConfidence() - CONTRADICTION_CONFIDENCE_PENALTY);
                if (memory.getConfidence() <= 0.2 || memory.getContradictionCount() >= 2) {
                    memory.setStatus(ExperienceMemoryStatus.DISABLED);
                }
                memory.setUpdatedAt(now);
                updated.add(memory);
            }
        }
        store.saveAll(memories);
        return updated;
    }

    public Optional<ExperienceMemory> pin(String id) {
        return updateUserState(id, ExperienceMemoryUserState.PINNED, ExperienceMemoryStatus.ACTIVE);
    }

    public Optional<ExperienceMemory> forget(String id) {
        return updateUserState(id, ExperienceMemoryUserState.FORGOTTEN, ExperienceMemoryStatus.DISABLED);
    }

    public Optional<ExperienceMemory> unpin(String id) {
        return updateUserState(id, ExperienceMemoryUserState.AUTO, ExperienceMemoryStatus.ACTIVE);
    }

    private Optional<ExperienceMemory> updateUserState(String id,
                                                       ExperienceMemoryUserState userState,
                                                       ExperienceMemoryStatus status) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        List<ExperienceMemory> memories = new ArrayList<>(store.list());
        Optional<ExperienceMemory> target = Optional.empty();
        for (ExperienceMemory memory : memories) {
            if (id.equals(memory.getId())) {
                memory.setUserState(userState);
                memory.setStatus(status);
                memory.setUpdatedAt(now);
                target = Optional.of(memory);
                break;
            }
        }
        target.ifPresent(ignored -> store.saveAll(memories));
        return target;
    }

    private ExperienceMemory newMemory(LearningCandidate candidate, ExperienceMemoryType type) {
        Instant now = Instant.now();
        ExperienceMemory memory = new ExperienceMemory();
        memory.setId(UUID.randomUUID().toString());
        memory.setSourceCandidateId(candidate.getId());
        memory.setType(type);
        memory.setStatus(ExperienceMemoryStatus.ACTIVE);
        memory.setTitle(candidate.getTitle());
        String taskPattern = firstNonBlank(metadataValue(candidate, "taskPattern"), candidate.getTitle());
        ExperienceTaskClassifier.TaskSignature signature = ExperienceTaskClassifier.classify(
                firstNonBlank(candidate.getTaskInput(), taskPattern, candidate.getProposal())
        );
        memory.setTaskPattern(firstNonBlank(normalizedKnown(signature.taskPattern()), taskPattern));
        memory.getMetadata().put("objectType", signature.objectType());
        memory.setApplicability(sanitize(candidate.getTaskInput()));
        memory.setMethodGuidance(sanitize(firstNonBlank(
                metadataValue(candidate, "methodGuidance"),
                candidate.getProposal()
        )));
        memory.setToolSequence(extractToolSequence(candidate));
        memory.setAvoidRules(type == ExperienceMemoryType.AVOID_RULE
                ? List.of(sanitize(firstNonBlank(metadataValue(candidate, "failureReason"), candidate.getReason(), candidate.getProposal())))
                : List.of());
        memory.setAvoidGuidance(type == ExperienceMemoryType.AVOID_RULE
                ? sanitize(firstNonBlank(metadataValue(candidate, "avoidGuidance"), candidate.getReason()))
                : "");
        memory.setProposal(sanitize(candidate.getProposal()));
        memory.setRiskLevel(firstNonBlank(metadataValue(candidate, "riskLevel"), "medium"));
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

    private String sanitize(String value) {
        return ExperienceMemorySanitizer.sanitize(value).text();
    }

    private String normalizedKnown(String value) {
        return value != null && !"unknown".equals(value) ? value : "";
    }
}

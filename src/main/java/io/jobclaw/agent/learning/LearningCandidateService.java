package io.jobclaw.agent.learning;

import io.jobclaw.agent.experience.ExperienceMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LearningCandidateService {

    private final LearningCandidateStore store;
    private final ExperienceMemoryService experienceMemoryService;

    @Autowired
    public LearningCandidateService(LearningCandidateStore store,
                                    ExperienceMemoryService experienceMemoryService) {
        this.store = store;
        this.experienceMemoryService = experienceMemoryService;
    }

    public LearningCandidateService(LearningCandidateStore store) {
        this(store, null);
    }

    public List<LearningCandidate> listPending() {
        return store.list().stream()
                .filter(candidate -> candidate.getStatus() == LearningCandidateStatus.PENDING)
                .toList();
    }

    public List<LearningCandidate> list(String status) {
        if (status == null || status.isBlank()) {
            return store.list();
        }
        LearningCandidateStatus parsedStatus = parseStatus(status);
        return store.list().stream()
                .filter(candidate -> candidate.getStatus() == parsedStatus)
                .toList();
    }

    public Optional<LearningCandidate> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.list().stream()
                .filter(candidate -> id.equals(candidate.getId()))
                .findFirst();
    }

    public Optional<LearningCandidate> markAccepted(String id) {
        Optional<LearningCandidate> accepted = updateStatus(id, LearningCandidateStatus.ACCEPTED);
        accepted.ifPresent(candidate -> {
            if (experienceMemoryService != null) {
                experienceMemoryService.applyAcceptedCandidate(candidate);
            }
        });
        return accepted;
    }

    public Optional<LearningCandidate> markRejected(String id) {
        return delete(id).map(candidate -> {
            candidate.setStatus(LearningCandidateStatus.REJECTED);
            candidate.setUpdatedAt(Instant.now());
            return candidate;
        });
    }

    public Optional<LearningCandidate> delete(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        for (int i = 0; i < candidates.size(); i++) {
            LearningCandidate candidate = candidates.get(i);
            if (id.equals(candidate.getId())) {
                candidates.remove(i);
                store.saveAll(candidates);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<LearningCandidate> updateStatus(String id, LearningCandidateStatus status) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<LearningCandidate> candidates = new ArrayList<>(store.list());
        for (LearningCandidate candidate : candidates) {
            if (id.equals(candidate.getId())) {
                candidate.setStatus(status);
                candidate.setUpdatedAt(Instant.now());
                store.saveAll(candidates);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private LearningCandidateStatus parseStatus(String status) {
        try {
            return LearningCandidateStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported learning candidate status: " + status, e);
        }
    }
}

package io.jobclaw.agent.learning;

import java.util.List;

public interface LearningCandidateStore {

    List<LearningCandidate> list();

    void saveAll(List<LearningCandidate> candidates);
}

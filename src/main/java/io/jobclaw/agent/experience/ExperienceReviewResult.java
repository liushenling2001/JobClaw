package io.jobclaw.agent.experience;

import java.nio.file.Path;
import java.time.Instant;

public record ExperienceReviewResult(
        Path reportPath,
        Path latestPath,
        int workflowCount,
        int pendingCandidateCount,
        int acceptedCandidateCount,
        int rejectedCandidateCount,
        Instant reviewedAt
) {
}

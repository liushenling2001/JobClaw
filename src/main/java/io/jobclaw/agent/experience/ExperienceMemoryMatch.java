package io.jobclaw.agent.experience;

public record ExperienceMemoryMatch(
        String id,
        ExperienceMemoryType type,
        String title,
        String guidance,
        double score,
        double confidence,
        boolean sanitized
) {
}

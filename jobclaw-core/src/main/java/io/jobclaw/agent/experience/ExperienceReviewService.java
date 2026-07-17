package io.jobclaw.agent.experience;

import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateStore;
import io.jobclaw.config.Config;
import io.jobclaw.config.ExperienceConfig;
import io.jobclaw.runtime.provider.SpringAiLlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Component
public class ExperienceReviewService {

    private static final DateTimeFormatter DAILY_REPORT_NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final Config config;
    private final LearningCandidateStore learningCandidateStore;
    private final ExperienceMemoryStore experienceMemoryStore;
    private final SpringAiLlmClient llmClient;

    @Autowired
    public ExperienceReviewService(Config config,
                                   LearningCandidateStore learningCandidateStore,
                                   ExperienceMemoryStore experienceMemoryStore,
                                   SpringAiLlmClient llmClient) {
        this.config = config;
        this.learningCandidateStore = learningCandidateStore;
        this.experienceMemoryStore = experienceMemoryStore;
        this.llmClient = llmClient;
    }

    public ExperienceReviewService(Config config,
                                   LearningCandidateStore learningCandidateStore) {
        this(config, learningCandidateStore, null, null);
    }

    public ExperienceReviewService(Config config,
                                   LearningCandidateStore learningCandidateStore,
                                   SpringAiLlmClient llmClient) {
        this(config, learningCandidateStore, null, llmClient);
    }

    public ExperienceReviewResult reviewNow() {
        try {
            Instant reviewedAt = Instant.now();
            Path experienceDir = Path.of(config.getWorkspacePath()).resolve(".jobclaw").resolve("experience");
            Files.createDirectories(experienceDir);

            List<LearningCandidate> candidates = learningCandidateStore.list();
            List<ExperienceMemory> acceptedMemories = experienceMemoryStore != null
                    ? experienceMemoryStore.list()
                    : List.of();

            Path reportPath = experienceDir.resolve("experience-review-" + DAILY_REPORT_NAME.format(reviewedAt) + ".md");
            Path latestPath = experienceDir.resolve("latest.md");
            String report = buildReport(reviewedAt, candidates, acceptedMemories);
            report = appendLlmRefinementIfEnabled(report, candidates);
            Files.writeString(reportPath, report);
            Files.writeString(latestPath, report);

            return new ExperienceReviewResult(
                    reportPath,
                    latestPath,
                    acceptedMemories.size(),
                    countCandidates(candidates, LearningCandidateStatus.PENDING),
                    countCandidates(candidates, LearningCandidateStatus.ACCEPTED),
                    countCandidates(candidates, LearningCandidateStatus.REJECTED),
                    reviewedAt
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run experience review: " + e.getMessage(), e);
        }
    }

    private String appendLlmRefinementIfEnabled(String report,
                                                List<LearningCandidate> candidates) {
        ExperienceConfig experienceConfig = config.getExperience();
        if (llmClient == null || experienceConfig == null || !experienceConfig.isLlmReviewEnabled()) {
            return report;
        }
        int pendingCandidates = countCandidates(candidates, LearningCandidateStatus.PENDING);
        if (pendingCandidates < Math.max(1, experienceConfig.getLlmReviewMinPendingCandidates())) {
            return report;
        }
        try {
            String refined = refineWithLlm(report, pendingCandidates, experienceConfig);
            if (refined == null || refined.isBlank() || refined.startsWith("Error:")) {
                return report + "\n\n## LLM Refined Insights\n\n- LLM review was skipped because the provider returned no usable content.\n";
            }
            return report + "\n\n## LLM Refined Insights\n\n" + refined.trim() + "\n";
        } catch (Exception e) {
            return report + "\n\n## LLM Refined Insights\n\n- LLM review failed and local evidence report remains authoritative: "
                    + e.getMessage() + "\n";
        }
    }

    private String refineWithLlm(String report,
                                 int pendingCandidateCount,
                                 ExperienceConfig experienceConfig) {
        String boundedReport = truncate(report, Math.max(2000, experienceConfig.getLlmReviewMaxInputChars()));
        String prompt = """
                You are refining JobClaw runtime experience evidence.

                Constraints:
                - Do not invent facts not present in the evidence.
                - Do not propose automatic code, memory, skill, or agent-profile changes.
                - Produce concise operational guidance only.
                - Separate negative lessons from reusable operating guidance.
                - Output Chinese unless the evidence is mostly English.

                Required sections:
                1. 需要避免的失败路径
                2. 可复用的成功流程
                3. 建议人工确认的候选

                Evidence summary:
                pendingCandidateCount=%d

                Evidence:
                %s
                """.formatted(pendingCandidateCount, boundedReport);
        return llmClient.complete(
                "You refine runtime experience reports into concise, safe operational guidance.",
                prompt,
                Math.max(256, experienceConfig.getLlmReviewMaxTokens()),
                0.2
        );
    }

    private String buildReport(Instant reviewedAt,
                               List<LearningCandidate> candidates,
                               List<ExperienceMemory> acceptedMemories) {
        StringBuilder sb = new StringBuilder();
        sb.append("# JobClaw Experience Review\n\n");
        sb.append("- Reviewed at: ").append(reviewedAt).append("\n");
        sb.append("- Pending candidates: ").append(countCandidates(candidates, LearningCandidateStatus.PENDING)).append("\n");
        sb.append("- Accepted candidates: ").append(countCandidates(candidates, LearningCandidateStatus.ACCEPTED)).append("\n");
        sb.append("- Rejected candidates: ").append(countCandidates(candidates, LearningCandidateStatus.REJECTED)).append("\n\n");

        appendAcceptedExperience(sb, acceptedMemories);
        appendNegativeLessons(sb, candidates);
        appendPendingCandidates(sb, candidates);
        appendOperatingRules(sb);
        return sb.toString();
    }

    private void appendAcceptedExperience(StringBuilder sb, List<ExperienceMemory> memories) {
        sb.append("## Accepted Experience Memory\n\n");
        List<ExperienceMemory> active = memories.stream()
                .filter(memory -> memory.getStatus() == ExperienceMemoryStatus.ACTIVE)
                .sorted(Comparator.comparing(ExperienceMemory::getConfidence).reversed())
                .limit(20)
                .toList();
        if (active.isEmpty()) {
            sb.append("- No accepted experience memory.\n\n");
            return;
        }
        for (ExperienceMemory memory : active) {
            sb.append("- ").append(blankToDefault(memory.getTitle(), "Accepted experience"))
                    .append(" | type=").append(memory.getType())
                    .append(" | confidence=").append(memory.getConfidence())
                    .append("\n");
            if (memory.getApplicability() != null && !memory.getApplicability().isBlank()) {
                sb.append("  Applies when: ").append(singleLine(memory.getApplicability())).append("\n");
            }
            if (!memory.getToolSequence().isEmpty()) {
                sb.append("  Tools: ").append(String.join(" -> ", memory.getToolSequence())).append("\n");
            }
            if (!memory.getAvoidRules().isEmpty()) {
                sb.append("  Avoid: ").append(singleLine(String.join("; ", memory.getAvoidRules()))).append("\n");
            }
        }
        sb.append("\n");
    }

    private void appendNegativeLessons(StringBuilder sb, List<LearningCandidate> candidates) {
        sb.append("## Negative Lessons\n\n");
        List<LearningCandidate> lessons = candidates.stream()
                .filter(candidate -> candidate.getStatus() == LearningCandidateStatus.PENDING)
                .filter(candidate -> candidate.getType() != null && "NEGATIVE_LESSON".equals(candidate.getType().name()))
                .sorted(Comparator.comparing(LearningCandidate::getConfidence).reversed())
                .limit(20)
                .toList();
        if (lessons.isEmpty()) {
            sb.append("- No pending negative lessons.\n\n");
            return;
        }
        for (LearningCandidate lesson : lessons) {
            sb.append("- ").append(blankToDefault(lesson.getTitle(), "Failure lesson"))
                    .append(" | confidence=").append(lesson.getConfidence())
                    .append("\n");
            if (lesson.getTaskInput() != null && !lesson.getTaskInput().isBlank()) {
                sb.append("  Task: ").append(singleLine(lesson.getTaskInput())).append("\n");
            }
            if (lesson.getReason() != null && !lesson.getReason().isBlank()) {
                sb.append("  Reason: ").append(singleLine(lesson.getReason())).append("\n");
            }
        }
        sb.append("\n");
    }

    private void appendPendingCandidates(StringBuilder sb, List<LearningCandidate> candidates) {
        sb.append("## Pending Learning Candidates\n\n");
        List<LearningCandidate> pending = candidates.stream()
                .filter(candidate -> candidate.getStatus() == LearningCandidateStatus.PENDING)
                .filter(candidate -> candidate.getType() == null || !"NEGATIVE_LESSON".equals(candidate.getType().name()))
                .sorted(Comparator.comparing(LearningCandidate::getConfidence).reversed())
                .limit(20)
                .toList();
        if (pending.isEmpty()) {
            sb.append("- No pending learning candidates.\n\n");
            return;
        }
        for (LearningCandidate candidate : pending) {
            sb.append("- ").append(blankToDefault(candidate.getTitle(), "Untitled candidate"))
                    .append(" | type=").append(candidate.getType())
                    .append(" | confidence=").append(candidate.getConfidence())
                    .append("\n");
            if (candidate.getReason() != null && !candidate.getReason().isBlank()) {
                sb.append("  Reason: ").append(singleLine(candidate.getReason())).append("\n");
            }
        }
        sb.append("\n");
    }

    private void appendOperatingRules(StringBuilder sb) {
        sb.append("## Runtime Rules\n\n");
        sb.append("- This review is evidence only; it does not rewrite memory, skills, agents, or core logic.\n");
        sb.append("- Promote candidates only after user approval or repeated successful runs.\n");
        sb.append("- Use accepted experience as guidance, not as a hard override when the current task conflicts.\n");
    }

    private int countCandidates(List<LearningCandidate> candidates, LearningCandidateStatus status) {
        return (int) candidates.stream()
                .filter(candidate -> candidate.getStatus() == status)
                .count();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String singleLine(String value) {
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n[truncated]";
    }
}

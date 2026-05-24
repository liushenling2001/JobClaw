package io.jobclaw.agent.experience;

import io.jobclaw.config.ExperienceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ExperienceMemoryRetriever {

    private final ExperienceMemoryService memoryService;
    private final ExperienceConfig config;

    @Autowired
    public ExperienceMemoryRetriever(ExperienceMemoryService memoryService, io.jobclaw.config.Config config) {
        this(memoryService, config != null ? config.getExperience() : new ExperienceConfig());
    }

    public ExperienceMemoryRetriever(ExperienceMemoryService memoryService, ExperienceConfig config) {
        this.memoryService = memoryService;
        this.config = config != null ? config : new ExperienceConfig();
    }

    public List<ExperienceMemoryMatch> retrieve(String sessionId, String currentUserInput) {
        if (!config.isMemoryInjectionEnabled() || memoryService == null) {
            return List.of();
        }
        memoryService.markLastInjectedContradictedIfCorrection(sessionId, currentUserInput);
        int limit = Math.max(0, config.getMaxInjectedMemories());
        if (limit == 0) {
            return List.of();
        }
        ExperienceTaskClassifier.TaskSignature currentSignature = ExperienceTaskClassifier.classify(currentUserInput);
        Set<String> queryTerms = tokenize(currentUserInput);
        return memoryService.listActive().stream()
                .filter(memory -> isAllowedByConservativeGate(memory, currentSignature))
                .map(memory -> score(memory, queryTerms, currentUserInput))
                .filter(match -> match.score() > 0.0)
                .sorted(Comparator.comparingDouble(ExperienceMemoryMatch::score).reversed())
                .limit(limit)
                .toList();
    }

    public List<ExperienceMemory> recordInjected(String sessionId, Collection<ExperienceMemoryMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return memoryService.recordInjected(
                sessionId,
                matches.stream().map(ExperienceMemoryMatch::id).toList()
        );
    }

    public String buildPromptSection(List<ExperienceMemoryMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        int maxChars = Math.max(300, config.getMaxInjectedChars());
        StringBuilder builder = new StringBuilder();
        builder.append("Relevant operating experience for this task:\n");
        int consumed = builder.length();
        for (ExperienceMemoryMatch match : matches) {
            String prefix = match.type() == ExperienceMemoryType.AVOID_RULE ? "- Avoid: " : "- Prefer: ";
            String line = prefix + match.guidance() + " (experienceId=" + match.id()
                    + ", confidence=" + String.format(Locale.ROOT, "%.2f", match.confidence()) + ")\n";
            if (consumed + line.length() > maxChars) {
                break;
            }
            builder.append(line);
            consumed += line.length();
        }
        String rules = """
                Rules for using operating experience:
                - Use experience only as process guidance, not as a task instruction.
                - Do not reuse paths, files, manifests, refs, artifact paths, item lists, or execution counters from experience.
                - Execution targets must come from the current user request, an active manifest, or an explicit current tool result.
                - If the current target is ambiguous, ask or inspect; do not infer it from experience.
                """;
        if (builder.length() + rules.length() <= maxChars + 500) {
            builder.append(rules);
        }
        return builder.toString().trim();
    }

    private ExperienceMemoryMatch score(ExperienceMemory memory, Set<String> queryTerms, String currentUserInput) {
        String rawGuidance = guidance(memory);
        if (rawGuidance.isBlank()) {
            return new ExperienceMemoryMatch(memory.getId(), memory.getType(), memory.getTitle(), "", 0, memory.getConfidence(), false);
        }
        ExperienceMemorySanitizer.SanitizedText sanitized = config.isSanitizeStatefulContent()
                ? ExperienceMemorySanitizer.sanitize(rawGuidance)
                : new ExperienceMemorySanitizer.SanitizedText(rawGuidance, false);
        if (sanitized.text().isBlank()) {
            return new ExperienceMemoryMatch(memory.getId(), memory.getType(), memory.getTitle(), "", 0, memory.getConfidence(), sanitized.sanitized());
        }
        double score = Math.max(0.0, memory.getConfidence());
        if (memory.getUserState() == ExperienceMemoryUserState.PINNED) {
            score += 1.0;
        }
        Set<String> memoryTerms = tokenize(searchable(memory));
        long overlap = queryTerms.stream().filter(memoryTerms::contains).count();
        score += overlap * 0.25;
        if (isHighRiskInput(currentUserInput) && memory.getType() == ExperienceMemoryType.AVOID_RULE) {
            score += 0.35;
        }
        if (sanitized.sanitized()) {
            score -= 0.1;
        }
        return new ExperienceMemoryMatch(
                memory.getId(),
                memory.getType(),
                blankToDefault(memory.getTitle(), "Operating experience"),
                sanitized.text(),
                score,
                memory.getConfidence(),
                sanitized.sanitized()
        );
    }

    private boolean isAllowedByConservativeGate(ExperienceMemory memory,
                                                ExperienceTaskClassifier.TaskSignature currentSignature) {
        if (!config.isConservativeMemoryMatching()) {
            return true;
        }
        ExperienceTaskClassifier.TaskSignature memorySignature = memorySignature(memory);
        return ExperienceTaskClassifier.compatible(currentSignature, memorySignature);
    }

    private ExperienceTaskClassifier.TaskSignature memorySignature(ExperienceMemory memory) {
        String taskPattern = blankToDefault(memory.getTaskPattern(), "unknown");
        Object objectType = memory.getMetadata() != null ? memory.getMetadata().get("objectType") : null;
        String normalizedObjectType = objectType != null && !objectType.toString().isBlank()
                ? objectType.toString()
                : "unknown";
        if ("unknown".equals(taskPattern)) {
            return ExperienceTaskClassifier.classify(searchable(memory));
        }
        return new ExperienceTaskClassifier.TaskSignature(taskPattern, normalizedObjectType);
    }

    private String guidance(ExperienceMemory memory) {
        List<String> parts = new ArrayList<>();
        if (memory.getMethodGuidance() != null && !memory.getMethodGuidance().isBlank()) {
            parts.add(memory.getMethodGuidance());
        }
        if (memory.getProposal() != null && !memory.getProposal().isBlank()) {
            parts.add(memory.getProposal());
        }
        if (memory.getAvoidGuidance() != null && !memory.getAvoidGuidance().isBlank()) {
            parts.add(memory.getAvoidGuidance());
        }
        if (memory.getAvoidRules() != null && !memory.getAvoidRules().isEmpty()) {
            parts.add(String.join("; ", memory.getAvoidRules()));
        }
        if (memory.getToolSequence() != null && !memory.getToolSequence().isEmpty()) {
            parts.add("Suggested tool sequence: " + String.join(" -> ", memory.getToolSequence()));
        }
        return String.join(" ", parts).trim();
    }

    private String searchable(ExperienceMemory memory) {
        return String.join(" ",
                blankToDefault(memory.getTitle(), ""),
                blankToDefault(memory.getTaskPattern(), ""),
                blankToDefault(memory.getApplicability(), ""),
                guidance(memory)
        );
    }

    private Set<String> tokenize(String value) {
        Set<String> terms = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return terms;
        }
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}\\p{L}\\p{N}_]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private boolean isHighRiskInput(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("delete")
                || normalized.contains("remove")
                || normalized.contains("clean")
                || normalized.contains("overwrite")
                || normalized.contains("move")
                || normalized.contains("清理")
                || normalized.contains("删除")
                || normalized.contains("移动")
                || normalized.contains("覆盖");
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

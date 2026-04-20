package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.evolution.MemoryEntry;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateStatus;
import io.jobclaw.agent.learning.LearningCandidateStore;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit long-term memory tool.
 *
 * User-facing durable memories go to memory/MEMORIES.json. Ordinary workspace
 * files remain task inputs/outputs, not implicit memory.
 */
@Component
public class MemoryTool {

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 8;

    private final MemoryStore memoryStore;
    private final LearningCandidateStore learningCandidateStore;

    @Autowired
    public MemoryTool(Config config, LearningCandidateStore learningCandidateStore) {
        this.memoryStore = new MemoryStore(config.getWorkspacePath());
        this.learningCandidateStore = learningCandidateStore;
    }

    public MemoryTool(Config config) {
        this.memoryStore = new MemoryStore(config.getWorkspacePath());
        this.learningCandidateStore = null;
    }

    @Tool(name = "memory", description = "Manage durable long-term memory. Use remember when the user explicitly asks to remember a fact, preference, rule, or future behavior. Supports actions: remember, search, list, forget.")
    public String execute(
            @ToolParam(description = "Operation: remember/search/list/forget") String action,
            @ToolParam(description = "Memory content for remember", required = false) String content,
            @ToolParam(description = "Search query for search", required = false) String query,
            @ToolParam(description = "Memory id for forget", required = false) String id,
            @ToolParam(description = "Comma-separated tags, for example user,project,preference", required = false) String tags,
            @ToolParam(description = "Memory scope: user/project/agent", required = false) String scope,
            @ToolParam(description = "Importance from 0.0 to 1.0. Defaults to 0.8 for explicit user memory.", required = false) Double importance
    ) {
        if (action == null || action.isBlank()) {
            return "Error: action is required (remember/search/list/forget)";
        }
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "remember" -> remember(content, tags, scope, importance);
            case "search" -> search(query);
            case "list" -> list();
            case "forget" -> forget(id);
            default -> "Error: unknown memory action '" + action + "'. Valid actions: remember, search, list, forget";
        };
    }

    private String remember(String content, String tags, String scope, Double importance) {
        if (content == null || content.isBlank()) {
            return "Error: content is required for memory remember";
        }
        List<String> parsedTags = parseTags(tags);
        String normalizedScope = normalizeScope(scope);
        if (!parsedTags.contains(normalizedScope)) {
            parsedTags.add(normalizedScope);
        }
        if (!parsedTags.contains("explicit")) {
            parsedTags.add("explicit");
        }
        double score = importance != null ? importance : 0.8d;
        String normalizedContent = content.trim();
        memoryStore.addEntry(normalizedContent, score, parsedTags, "user_explicit");
        Optional<LearningCandidate> candidate = maybeRecordLearningCandidate(
                normalizedContent,
                parsedTags,
                normalizedScope,
                score
        );
        String suffix = candidate
                .map(value -> "\nLearning candidate created: " + value.getId() + " (" + value.getType() + ", PENDING)")
                .orElse("");
        return "Remembered durable memory (" + normalizedScope + "): " + normalizedContent + suffix;
    }

    private String search(String query) {
        if (query == null || query.isBlank()) {
            return "Error: query is required for memory search";
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<MemoryEntry> matches = memoryStore.getEntries().stream()
                .filter(entry -> entry.getContent() != null
                        && entry.getContent().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .sorted(Comparator.comparingDouble(MemoryEntry::computeScore).reversed())
                .limit(DEFAULT_SEARCH_LIMIT)
                .toList();
        if (matches.isEmpty()) {
            return "No matching memories found.";
        }
        return formatEntries("Matching memories", matches);
    }

    private String list() {
        List<MemoryEntry> entries = memoryStore.getEntries().stream()
                .sorted(Comparator.comparingDouble(MemoryEntry::computeScore).reversed())
                .limit(DEFAULT_LIST_LIMIT)
                .toList();
        if (entries.isEmpty()) {
            return "No durable memories stored.";
        }
        return formatEntries("Durable memories", entries);
    }

    private String forget(String id) {
        if (id == null || id.isBlank()) {
            return "Error: id is required for memory forget";
        }
        MemoryEntry removed = memoryStore.removeEntry(id.trim());
        if (removed == null) {
            return "Error: memory not found: " + id.trim();
        }
        return "Forgot memory: " + removed.getContent();
    }

    private List<String> parseTags(String tags) {
        List<String> parsed = new ArrayList<>();
        if (tags == null || tags.isBlank()) {
            return parsed;
        }
        for (String tag : tags.split("[,，\\s]+")) {
            String normalized = tag.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank() && !parsed.contains(normalized)) {
                parsed.add(normalized);
            }
        }
        return parsed;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "user";
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("project") || normalized.equals("agent") || normalized.equals("user")) {
            return normalized;
        }
        return "user";
    }

    private Optional<LearningCandidate> maybeRecordLearningCandidate(String content,
                                                                     List<String> tags,
                                                                     String scope,
                                                                     double importance) {
        if (learningCandidateStore == null || content == null || content.isBlank()) {
            return Optional.empty();
        }
        LearningCandidateType type = classifyExperienceCandidate(content, tags);
        if (type == null) {
            return Optional.empty();
        }
        List<LearningCandidate> candidates = new ArrayList<>(learningCandidateStore.list());
        String fingerprint = learningFingerprint(content, type);
        boolean exists = candidates.stream().anyMatch(candidate ->
                type == candidate.getType()
                        && fingerprint.equals(candidate.getMetadata().get("explicitMemoryFingerprint")));
        if (exists) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId(UUID.randomUUID().toString());
        candidate.setType(type);
        candidate.setStatus(LearningCandidateStatus.PENDING);
        candidate.setTitle(type == LearningCandidateType.NEGATIVE_LESSON
                ? "用户显式记录的失败经验候选"
                : "用户显式记录的工作流经验候选");
        candidate.setReason("用户通过 memory 工具显式要求记录经验。先作为学习候选等待确认，不直接改写正式经验。");
        candidate.setSessionId(AgentExecutionContext.getCurrentSessionKey());
        candidate.setSourceRunId(AgentExecutionContext.getCurrentRunId());
        candidate.setTaskInput(content);
        candidate.setPlanningMode(TaskPlanningMode.DIRECT);
        candidate.setDeliveryType(null);
        candidate.setProposal(buildExplicitMemoryProposal(content, type));
        candidate.setTags(buildCandidateTags(tags, type));
        candidate.setConfidence(Math.max(0.5d, Math.min(0.9d, importance)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "memory_tool_explicit");
        metadata.put("scope", scope);
        metadata.put("explicitMemoryFingerprint", fingerprint);
        metadata.put("toolSequence", List.of("memory"));
        candidate.setMetadata(metadata);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);

        candidates.add(candidate);
        learningCandidateStore.saveAll(candidates);
        return Optional.of(candidate);
    }

    private LearningCandidateType classifyExperienceCandidate(String content, List<String> tags) {
        String text = (content + " " + String.join(" ", tags)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "失败", "教训", "踩坑", "错误", "避免", "不要再", "不能再", "negative", "avoid", "failure", "lesson")) {
            return LearningCandidateType.NEGATIVE_LESSON;
        }
        if (containsAny(text, "经验", "工作流", "流程", "步骤", "以后按", "以后要", "下次", "复用", "总结", "workflow", "process", "procedure")) {
            return LearningCandidateType.WORKFLOW;
        }
        return null;
    }

    private boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String learningFingerprint(String content, LearningCandidateType type) {
        return type.name() + ":" + content.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[`\"'“”‘’]", "");
    }

    private String buildExplicitMemoryProposal(String content, LearningCandidateType type) {
        if (type == LearningCandidateType.NEGATIVE_LESSON) {
            return "User explicitly recorded a lesson to avoid repeating a failed path:\n"
                    + content
                    + "\nUse as candidate negative guidance only after confirmation.";
        }
        return "User explicitly recorded a reusable workflow or experience:\n"
                + content
                + "\nUse as candidate workflow guidance only after confirmation.";
    }

    private List<String> buildCandidateTags(List<String> tags, LearningCandidateType type) {
        List<String> candidateTags = new ArrayList<>();
        candidateTags.add("explicit_memory");
        candidateTags.add(type == LearningCandidateType.NEGATIVE_LESSON ? "negative_lesson" : "workflow");
        for (String tag : tags) {
            if (tag != null && !tag.isBlank() && !candidateTags.contains(tag)) {
                candidateTags.add(tag);
            }
        }
        return candidateTags;
    }

    private String formatEntries(String title, List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder(title).append(":\n\n");
        for (MemoryEntry entry : entries) {
            sb.append("- id=").append(entry.getId()).append(" ");
            if (entry.getTags() != null && !entry.getTags().isEmpty()) {
                sb.append(entry.getTags()).append(" ");
            }
            sb.append(entry.getContent()).append("\n");
        }
        return sb.toString();
    }
}

package io.jobclaw.agent.skill;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.jobclaw.agent.manifest.ActiveManifestRegistry;

/**
 * Tracks the skill currently activated by a run.
 *
 * This is intentionally only a context guide. It does not block tools, decide
 * task completion, or parse business-specific skill steps.
 */
@Component
public class ActiveSkillRegistry {

    private static final int MAX_FRAME_CHARS = 3000;
    private static final int MAX_MANAGED_RUNTIME_CHARS = 5000;
    private static final Pattern RUNTIME_HEADING = Pattern.compile(
            "(?im)^(#{2,6})\\s*(Runtime Frame|运行帧|执行帧|运行上下文)\\s*$"
    );
    private static final Pattern MANAGED_RUNTIME_HEADING = Pattern.compile(
            "(?im)^(#{2,6})\\s*(Managed Runtime|Managed Runtime Protocol|托管运行|托管执行|托管运行协议)\\s*$"
    );

    private final Map<String, ActiveSkillState> states = new ConcurrentHashMap<>();

    public ActiveSkillState activate(String sessionKey, String runId, String skillName, String skillContent, String basePath) {
        if (isBlank(sessionKey) || isBlank(runId) || isBlank(skillName)) {
            return null;
        }
        String runtimeFrame = extractRuntimeFrame(skillContent);
        ManagedRuntime managedRuntime = extractManagedRuntime(skillContent);
        ActiveSkillState state = new ActiveSkillState(
                sessionKey,
                runId,
                skillName,
                basePath,
                runtimeFrame,
                managedRuntime,
                Instant.now()
        );
        states.put(key(sessionKey, runId), state);
        return state;
    }

    public ActiveSkillState get(String sessionKey, String runId) {
        if (isBlank(sessionKey) || isBlank(runId)) {
            return null;
        }
        return states.get(key(sessionKey, runId));
    }

    public void clear(String sessionKey, String runId) {
        if (isBlank(sessionKey) || isBlank(runId)) {
            return;
        }
        states.remove(key(sessionKey, runId));
    }

    public String formatForPrompt(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        return state != null ? state.toPromptFrame() : "";
    }

    public boolean hasManagedRuntime(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        return state != null && state.managedRuntime() != null && !state.managedRuntime().isEmpty();
    }

    public boolean hasManagedRunnerRuntime(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        return state != null && state.managedRuntime() != null && state.managedRuntime().runnerMode();
    }

    public int managedRunnerParallelism(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null || !state.managedRuntime().runnerMode()) {
            return 1;
        }
        return state.managedRuntime().parallelism();
    }

    public String renderManagedRuntime(String sessionKey,
                                       String runId,
                                       String phase,
                                       ActiveManifestRegistry.ActiveManifestState manifestState) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null || state.managedRuntime().isEmpty()) {
            return "";
        }
        return state.managedRuntime().render(phase, variables(state, manifestState));
    }

    static String extractRuntimeFrame(String skillContent) {
        if (skillContent == null || skillContent.isBlank()) {
            return "";
        }

        Matcher matcher = RUNTIME_HEADING.matcher(skillContent);
        if (!matcher.find()) {
            return "";
        }

        int headingLevel = matcher.group(1).length();
        int frameStart = matcher.end();
        Pattern nextHeading = Pattern.compile("(?m)^#{1," + headingLevel + "}\\s+.+$");
        Matcher nextMatcher = nextHeading.matcher(skillContent);
        int frameEnd = skillContent.length();
        if (nextMatcher.find(frameStart)) {
            frameEnd = nextMatcher.start();
        }

        return trimFrame(skillContent.substring(frameStart, frameEnd));
    }

    static ManagedRuntime extractManagedRuntime(String skillContent) {
        if (skillContent == null || skillContent.isBlank()) {
            return ManagedRuntime.empty();
        }

        String section = extractHeadingSection(skillContent, MANAGED_RUNTIME_HEADING);
        if (section.isBlank()) {
            return ManagedRuntime.empty();
        }
        String itemLoop = extractSubsection(section, "Item Loop|Item Runtime|单项循环|单项执行|项目循环");
        String finalizeTemplate = extractSubsection(section, "Finalize|Finalise|收尾|最终生成|完成阶段");
        String fallback = trimManagedRuntime(section);
        String mode = extractMode(section);
        int parallelism = extractParallelism(section);
        return new ManagedRuntime(mode, parallelism, itemLoop, finalizeTemplate, fallback);
    }

    private static String extractMode(String section) {
        if (section == null || section.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?im)^\\s*(mode|runner)\\s*[:=]\\s*([A-Za-z0-9_-]+|true)\\s*$").matcher(section);
        if (!matcher.find()) {
            return "";
        }
        String key = matcher.group(1) != null ? matcher.group(1).trim().toLowerCase() : "";
        String value = matcher.group(2) != null ? matcher.group(2).trim().toLowerCase() : "";
        if ("runner".equals(key) && "true".equals(value)) {
            return "runner";
        }
        return value;
    }

    private static int extractParallelism(String section) {
        if (section == null || section.isBlank()) {
            return 1;
        }
        Matcher matcher = Pattern.compile("(?im)^\\s*(parallelism|并行度)\\s*[:=]\\s*(\\d+)\\s*$").matcher(section);
        if (!matcher.find()) {
            return 1;
        }
        try {
            int value = Integer.parseInt(matcher.group(2));
            return Math.max(1, Math.min(4, value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String extractHeadingSection(String content, Pattern headingPattern) {
        Matcher matcher = headingPattern.matcher(content);
        if (!matcher.find()) {
            return "";
        }
        int headingLevel = matcher.group(1).length();
        int start = matcher.end();
        Pattern nextHeading = Pattern.compile("(?m)^#{1," + headingLevel + "}\\s+.+$");
        Matcher nextMatcher = nextHeading.matcher(content);
        int end = content.length();
        if (nextMatcher.find(start)) {
            end = nextMatcher.start();
        }
        return content.substring(start, end).strip();
    }

    private static String extractSubsection(String section, String headingAlternatives) {
        if (section == null || section.isBlank()) {
            return "";
        }
        Pattern heading = Pattern.compile("(?im)^(#{3,6})\\s*(" + headingAlternatives + ")\\s*$");
        Matcher matcher = heading.matcher(section);
        if (!matcher.find()) {
            return "";
        }
        int headingLevel = matcher.group(1).length();
        int start = matcher.end();
        Pattern nextHeading = Pattern.compile("(?m)^#{3," + headingLevel + "}\\s+.+$");
        Matcher nextMatcher = nextHeading.matcher(section);
        int end = section.length();
        if (nextMatcher.find(start)) {
            end = nextMatcher.start();
        }
        return trimManagedRuntime(section.substring(start, end));
    }

    private static String trimFrame(String frame) {
        if (frame == null) {
            return "";
        }
        String normalized = frame.strip();
        if (normalized.length() <= MAX_FRAME_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_FRAME_CHARS)
                + "\n...[runtime frame truncated; keep following the skill and re-invoke it if more detail is needed]";
    }

    private static String trimManagedRuntime(String frame) {
        if (frame == null) {
            return "";
        }
        String normalized = frame.strip();
        if (normalized.length() <= MAX_MANAGED_RUNTIME_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_MANAGED_RUNTIME_CHARS)
                + "\n...[managed runtime truncated; keep this card short in the skill]";
    }

    private Map<String, String> variables(ActiveSkillState skillState,
                                          ActiveManifestRegistry.ActiveManifestState manifestState) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("skill.name", skillState.skillName());
        vars.put("skill.basePath", safe(skillState.basePath()));
        if (manifestState == null) {
            return vars;
        }
        ActiveManifestRegistry.ActiveManifestItem item = manifestState.runningItem() != null
                ? manifestState.runningItem()
                : manifestState.nextPendingItem();
        vars.put("manifestId", manifestState.manifestId());
        vars.put("taskKey", manifestState.taskKey());
        vars.put("task.inputDir", taskKeyValue(manifestState.taskKey(), "inputDir"));
        vars.put("schema", manifestState.schema());
        vars.put("artifactPath", manifestState.artifactPath());
        vars.put("intermediateArtifactPath", manifestState.artifactPath());
        vars.put("finalArtifactPath", manifestState.finalArtifactPath());
        vars.put("finalArtifactType", manifestState.finalArtifactType());
        vars.put("counts.total", String.valueOf(manifestState.total()));
        vars.put("counts.pending", String.valueOf(manifestState.pending()));
        vars.put("counts.running", String.valueOf(manifestState.running()));
        vars.put("counts.done", String.valueOf(manifestState.done()));
        vars.put("counts.failed", String.valueOf(manifestState.failed()));
        if (item != null) {
            vars.put("item.id", item.id());
            vars.put("item.safeId", safePathName(item.id()));
            vars.put("item.title", item.title());
            vars.put("item.path", item.title());
            vars.put("item.status", item.status());
            vars.put("item.artifactPath", item.artifactPath());
            vars.put("item.resultRefId", item.resultRefId());
            vars.put("item.note", item.note());
            vars.put("item.error", item.error());
        }
        return vars;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String taskKeyValue(String taskKey, String name) {
        if (taskKey == null || taskKey.isBlank() || name == null || name.isBlank()) {
            return "";
        }
        String prefix = name + "=";
        for (String part : taskKey.split("\\|")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String safePathName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String key(String sessionKey, String runId) {
        return sessionKey + "::" + runId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ActiveSkillState(
            String sessionKey,
            String runId,
            String skillName,
            String basePath,
            String runtimeFrame,
            ManagedRuntime managedRuntime,
            Instant activatedAt
    ) {
        public String toPromptFrame() {
            StringBuilder sb = new StringBuilder();
            sb.append("[[JOBCLAW_ACTIVE_SKILL_FRAME]]\n");
            sb.append("Active skill for this run: ").append(skillName).append("\n");
            if (basePath != null && !basePath.isBlank()) {
                sb.append("Skill base path: ").append(basePath).append("\n");
            }
            sb.append("Use this as current-run guidance only. Do not reuse historical paths, schemas, manifest ids, artifact paths, or pending/done state unless the user explicitly asked to continue that exact task.\n");
            if (runtimeFrame != null && !runtimeFrame.isBlank()) {
                sb.append("\nRuntime frame from the skill:\n");
                sb.append(runtimeFrame).append("\n");
            } else {
                sb.append("\nThe skill did not declare a Runtime Frame. If the task starts drifting, invoke the skill again instead of inventing a new workflow.\n");
            }
            return sb.toString();
        }

        public String toToolFrame() {
            StringBuilder sb = new StringBuilder();
            sb.append("<active-skill-frame>\n");
            sb.append("<name>").append(skillName).append("</name>\n");
            if (basePath != null && !basePath.isBlank()) {
                sb.append("<base-path>").append(basePath).append("</base-path>\n");
            }
            sb.append("<rule>Keep following this skill during the current run. Task parameters must come from the current user request or current-run tool results.</rule>\n");
            if (runtimeFrame != null && !runtimeFrame.isBlank()) {
                sb.append("<runtime-frame>\n").append(runtimeFrame).append("\n</runtime-frame>\n");
            }
            if (managedRuntime != null && !managedRuntime.isEmpty()) {
                sb.append("<managed-runtime>declared</managed-runtime>\n");
            }
            sb.append("</active-skill-frame>\n");
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ActiveSkillState that)) {
                return false;
            }
            return Objects.equals(sessionKey, that.sessionKey)
                    && Objects.equals(runId, that.runId)
                    && Objects.equals(skillName, that.skillName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionKey, runId, skillName);
        }
    }

    public record ManagedRuntime(String mode, int parallelism, String itemLoop, String finalizeTemplate, String fallback) {
        static ManagedRuntime empty() {
            return new ManagedRuntime("", 1, "", "", "");
        }

        boolean isEmpty() {
            return isBlank(itemLoop) && isBlank(finalizeTemplate) && isBlank(fallback);
        }

        boolean runnerMode() {
            return "runner".equalsIgnoreCase(mode) && !isEmpty();
        }

        String render(String phase, Map<String, String> variables) {
            String template = switch (phase != null ? phase : "") {
                case "finalize" -> !isBlank(finalizeTemplate) ? finalizeTemplate : fallback;
                case "item" -> !isBlank(itemLoop) ? itemLoop : fallback;
                default -> fallback;
            };
            if (isBlank(template)) {
                return "";
            }
            String rendered = template;
            if (variables != null) {
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    rendered = rendered.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
                }
            }
            return trimManagedRuntime(rendered);
        }
    }
}

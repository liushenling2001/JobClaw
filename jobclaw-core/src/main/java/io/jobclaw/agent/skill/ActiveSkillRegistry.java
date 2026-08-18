package io.jobclaw.agent.skill;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Pattern ARTIFACT_COMPLETION_HEADING = Pattern.compile(
            "(?im)^(#{2,6})\\s*(Artifact Completion|Completion Contract|Artifact Contract|产物完成|产物要求|完成产物)\\s*$"
    );

    private final Map<String, ActiveSkillState> states = new ConcurrentHashMap<>();

    public ActiveSkillState activate(String sessionKey, String runId, String skillName, String skillContent, String basePath) {
        if (isBlank(sessionKey) || isBlank(runId) || isBlank(skillName)) {
            return null;
        }
        String runtimeFrame = extractRuntimeFrame(skillContent);
        ManagedRuntime managedRuntime = extractManagedRuntime(skillContent);
        ArtifactCompletion artifactCompletion = extractArtifactCompletion(skillContent);
        ActiveSkillState state = new ActiveSkillState(
                sessionKey,
                runId,
                skillName,
                basePath,
                runtimeFrame,
                managedRuntime,
                artifactCompletion,
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

    public boolean hasManagedRunnerContract(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        return state != null
                && state.managedRuntime() != null
                && state.managedRuntime().runnerMode()
                && (!state.managedRuntime().writesItemFile()
                        || !isBlank(state.managedRuntime().itemResultPathTemplate()))
                && (!state.managedRuntime().writesAggregate()
                        || !isBlank(state.managedRuntime().aggregatePathTemplate()))
                && !isBlank(state.managedRuntime().itemOutput());
    }

    public String managedRunnerContractError(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null || !state.managedRuntime().runnerMode()) {
            return "active skill does not declare Managed Runtime mode: runner";
        }
        ManagedRuntime runtime = state.managedRuntime();
        StringBuilder sb = new StringBuilder();
        if (runtime.writesItemFile() && isBlank(runtime.itemResultPathTemplate())) {
            sb.append("itemResultPathTemplate is required when resultSink is item_file or both; ");
        }
        if (runtime.writesAggregate() && isBlank(runtime.aggregatePathTemplate())) {
            sb.append("aggregatePathTemplate is required when aggregateSink is not none; ");
        }
        if (isBlank(runtime.itemOutput())) {
            sb.append("itemOutput is required; ");
        }
        return sb.isEmpty() ? "" : sb.toString().trim();
    }

    public String renderManagedItemResultPath(String sessionKey,
                                              String runId,
                                              ActiveManifestRegistry.ActiveManifestState manifestState) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null) {
            return "";
        }
        return renderTemplate(state.managedRuntime().itemResultPathTemplate(), variables(state, manifestState)).trim();
    }

    public String managedRunnerItemOutput(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null) {
            return "";
        }
        return safe(state.managedRuntime().itemOutput()).trim().toLowerCase();
    }

    public String managedRunnerResultSink(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null) {
            return "context_ref";
        }
        return state.managedRuntime().effectiveResultSink();
    }

    public String managedRunnerAggregateSink(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null) {
            return "none";
        }
        return state.managedRuntime().effectiveAggregateSink();
    }

    public boolean managedRunnerWritesItemFile(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        return state != null && state.managedRuntime() != null && state.managedRuntime().writesItemFile();
    }

    public boolean managedRunnerWritesAggregate(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        return state != null && state.managedRuntime() != null && state.managedRuntime().writesAggregate();
    }

    public List<String> managedRunnerAllowedTools(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.managedRuntime() == null) {
            return List.of();
        }
        return state.managedRuntime().allowedTools();
    }

    public ArtifactCompletion artifactCompletion(String sessionKey, String runId) {
        ActiveSkillState state = get(sessionKey, runId);
        if (state == null || state.artifactCompletion() == null) {
            return ArtifactCompletion.unspecified();
        }
        return state.artifactCompletion();
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
        String frameworkWrites = extractScalar(section, "frameworkWrites|framework_writes|框架写入");
        String itemResultPathTemplate = extractScalar(section, "itemResultPathTemplate|item_result_path_template|itemArtifactPathTemplate|item_artifact_path_template|单项结果路径模板");
        String aggregatePathTemplate = extractScalar(section, "aggregatePathTemplate|aggregate_path_template|JSONLPathTemplate|jsonl_path_template|聚合结果路径模板");
        String itemOutput = extractScalar(section, "itemOutput|item_output|单项输出");
        String resultSink = extractScalar(section, "resultSink|result_sink|结果存储|结果落点");
        String aggregateSink = extractScalar(section, "aggregateSink|aggregate_sink|聚合存储|聚合落点");
        List<String> allowedTools = extractList(section, "allowedTools|allowed_tools|tools|工具白名单|允许工具");
        return new ManagedRuntime(mode, parallelism, frameworkWrites, itemResultPathTemplate,
                aggregatePathTemplate, itemOutput, resultSink, aggregateSink, allowedTools,
                itemLoop, finalizeTemplate, fallback);
    }

    static ArtifactCompletion extractArtifactCompletion(String skillContent) {
        if (skillContent == null || skillContent.isBlank()) {
            return ArtifactCompletion.unspecified();
        }
        String section = extractHeadingSection(skillContent, ARTIFACT_COMPLETION_HEADING);
        if (section.isBlank()) {
            return ArtifactCompletion.unspecified();
        }
        String requiredValue = extractScalar(section,
                "requiresArtifact|requires_artifact|artifactRequired|artifact_required|requiredArtifact|required_artifact|产物必需|需要产物|是否需要产物");
        Boolean required = parseBooleanPolicy(requiredValue);
        if (required == null) {
            required = parseBooleanPolicy(section);
        }
        String artifactType = extractScalar(section, "artifactType|artifact_type|type|产物类型");
        String artifactPathTemplate = extractScalar(section,
                "artifactPathTemplate|artifact_path_template|finalArtifactPathTemplate|final_artifact_path_template|outputPathTemplate|output_path_template|产物路径模板");
        String note = trimFrame(section);
        return new ArtifactCompletion(true, required, artifactType, artifactPathTemplate, note);
    }

    private static Boolean parseBooleanPolicy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase()
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "");
        if (normalized.matches("(?s).*(\\btrue\\b|\\byes\\b|\\brequired\\b|\\brequire\\b|\\bmust\\b|需要|必需|必须).*")) {
            return true;
        }
        if (normalized.matches("(?s).*(\\bfalse\\b|\\bno\\b|\\bnone\\b|\\boff\\b|\\boptional\\b|不需要|无需|没有产物|无产物).*")) {
            return false;
        }
        return null;
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

    private static String extractScalar(String section, String keyAlternatives) {
        if (section == null || section.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?im)^\\s*(?:" + keyAlternatives + ")\\s*[:=]\\s*(.+?)\\s*$")
                .matcher(section);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1)
                .replace("`", "")
                .trim();
    }

    private static List<String> extractList(String section, String keyAlternatives) {
        String scalar = extractScalar(section, keyAlternatives);
        if (scalar.isBlank()) {
            return List.of();
        }
        String cleaned = scalar
                .replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace("'", "");
        List<String> values = new ArrayList<>();
        for (String part : cleaned.split("[,，]")) {
            String value = part.trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
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
        vars.putAll(taskKeyVariables(manifestState.taskKey()));
        vars.put("schema", manifestState.schema());
        vars.put("artifactPath", manifestState.artifactPath());
        vars.put("intermediateArtifactPath", manifestState.artifactPath());
        vars.put("artifactDir", parentPath(manifestState.artifactPath()));
        vars.put("intermediateArtifactDir", parentPath(manifestState.artifactPath()));
        vars.put("finalArtifactPath", manifestState.finalArtifactPath());
        vars.put("finalArtifactDir", parentPath(manifestState.finalArtifactPath()));
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

    private static Map<String, String> taskKeyVariables(String taskKey) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (taskKey == null || taskKey.isBlank()) {
            return vars;
        }
        for (String part : taskKey.split("\\|")) {
            String trimmed = part.trim();
            int index = trimmed.indexOf('=');
            if (index <= 0 || index >= trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if (!key.isBlank()) {
                vars.put("task." + key, value);
            }
        }
        return vars;
    }

    private static String parentPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            java.nio.file.Path parent = java.nio.file.Path.of(path.trim()).getParent();
            return parent != null ? parent.toString() : "";
        } catch (Exception ignored) {
            return "";
        }
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
            ArtifactCompletion artifactCompletion,
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
            if (artifactCompletion != null && artifactCompletion.declared()) {
                sb.append("<artifact-completion requiresArtifact=\"")
                        .append(artifactCompletion.required() != null ? artifactCompletion.required() : "auto")
                        .append("\"");
                if (!isBlank(artifactCompletion.artifactType())) {
                    sb.append(" artifactType=\"").append(artifactCompletion.artifactType()).append("\"");
                }
                sb.append(" />\n");
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

    public record ArtifactCompletion(boolean declared,
                                     Boolean required,
                                     String artifactType,
                                     String artifactPathTemplate,
                                     String note) {
        static ArtifactCompletion unspecified() {
            return new ArtifactCompletion(false, null, "", "", "");
        }

        public boolean requiresArtifact() {
            return Boolean.TRUE.equals(required);
        }

        public boolean disablesArtifactGuard() {
            return Boolean.FALSE.equals(required);
        }
    }

    private static String renderTemplate(String template, Map<String, String> variables) {
        if (isBlank(template)) {
            return "";
        }
        String rendered = template;
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
            }
        }
        return rendered;
    }

    public record ManagedRuntime(String mode,
                                 int parallelism,
                                 String frameworkWrites,
                                 String itemResultPathTemplate,
                                 String aggregatePathTemplate,
                                 String itemOutput,
                                 String resultSink,
                                 String aggregateSink,
                                 List<String> allowedTools,
                                 String itemLoop,
                                 String finalizeTemplate,
                                 String fallback) {
        static ManagedRuntime empty() {
            return new ManagedRuntime("", 1, "", "", "", "", "", "", List.of(), "", "", "");
        }

        boolean isEmpty() {
            return isBlank(itemLoop) && isBlank(finalizeTemplate) && isBlank(fallback);
        }

        boolean runnerMode() {
            return "runner".equalsIgnoreCase(mode) && !isEmpty();
        }

        String effectiveResultSink() {
            String normalized = normalizeSink(resultSink);
            if (!normalized.isBlank()) {
                return normalized;
            }
            return isBlank(itemResultPathTemplate) ? "context_ref" : "both";
        }

        String effectiveAggregateSink() {
            String normalized = normalizeSink(aggregateSink);
            if (!normalized.isBlank()) {
                return normalized;
            }
            return isBlank(aggregatePathTemplate) ? "none" : "jsonl";
        }

        boolean writesItemFile() {
            String sink = effectiveResultSink();
            return "item_file".equals(sink) || "both".equals(sink);
        }

        boolean writesAggregate() {
            return !"none".equals(effectiveAggregateSink());
        }

        private static String normalizeSink(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            String normalized = value.trim().toLowerCase()
                    .replace("-", "_")
                    .replace(" ", "_");
            return switch (normalized) {
                case "contextref", "context_ref", "ref" -> "context_ref";
                case "itemfile", "item_file", "file" -> "item_file";
                case "both", "all" -> "both";
                case "none", "off" -> "none";
                case "jsonl", "markdown", "md" -> normalized;
                case "json_array", "jsonarray" -> "json_array";
                default -> normalized;
            };
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
            String rendered = renderTemplate(template, variables);
            return trimManagedRuntime(rendered);
        }
    }
}

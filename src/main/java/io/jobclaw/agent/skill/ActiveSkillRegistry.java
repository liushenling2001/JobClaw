package io.jobclaw.agent.skill;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the skill currently activated by a run.
 *
 * This is intentionally only a context guide. It does not block tools, decide
 * task completion, or parse business-specific skill steps.
 */
@Component
public class ActiveSkillRegistry {

    private static final int MAX_FRAME_CHARS = 3000;
    private static final Pattern RUNTIME_HEADING = Pattern.compile(
            "(?im)^(#{2,6})\\s*(Runtime Frame|运行帧|执行帧|运行上下文)\\s*$"
    );

    private final Map<String, ActiveSkillState> states = new ConcurrentHashMap<>();

    public ActiveSkillState activate(String sessionKey, String runId, String skillName, String skillContent, String basePath) {
        if (isBlank(sessionKey) || isBlank(runId) || isBlank(skillName)) {
            return null;
        }
        String runtimeFrame = extractRuntimeFrame(skillContent);
        ActiveSkillState state = new ActiveSkillState(
                sessionKey,
                runId,
                skillName,
                basePath,
                runtimeFrame,
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
}

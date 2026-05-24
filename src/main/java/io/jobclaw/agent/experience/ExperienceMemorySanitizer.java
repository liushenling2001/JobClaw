package io.jobclaw.agent.experience;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ExperienceMemorySanitizer {

    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)[A-Z]:\\\\[^\\s\"'，。；;]+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<!\\w)(?:/|\\./|\\.\\./)[^\\s\"'，。；;]+");
    private static final Pattern CONTEXT_REF = Pattern.compile("(?i)\\b(?:context_ref|refId|ref_id)\\s*[:=]\\s*[^\\s,，;；]+");
    private static final Pattern MANIFEST_ID = Pattern.compile("(?i)\\bmanifestId\\s*[:=]\\s*[^\\s,，;；]+");
    private static final Pattern ARTIFACT_PATH = Pattern.compile("(?i)\\b(?:artifactPath|finalArtifactPath|inputDir|outputDir|output_path|intermediate_path)\\s*[:=]\\s*[^\\s,，;；]+");
    private static final Pattern EXECUTION_COUNTER = Pattern.compile("(?i)\\b(?:pending|running|done|failed)\\s*[:=]\\s*\\d+");
    private static final Pattern FILE_EXTENSION = Pattern.compile("(?i)\\b[^\\s\"'，。；;]+\\.(?:jsonl|xlsx|xls|pdf|docx|csv|zip|tmp|log)\\b");

    private ExperienceMemorySanitizer() {
    }

    public static SanitizedText sanitize(String text) {
        if (text == null || text.isBlank()) {
            return new SanitizedText("", false);
        }
        String sanitized = text;
        boolean changed = containsStatefulContent(sanitized);
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("[current-target-path]");
        sanitized = UNIX_PATH.matcher(sanitized).replaceAll("[current-target-path]");
        sanitized = CONTEXT_REF.matcher(sanitized).replaceAll("[current-context-ref]");
        sanitized = MANIFEST_ID.matcher(sanitized).replaceAll("manifestId=[current-manifest]");
        sanitized = ARTIFACT_PATH.matcher(sanitized).replaceAll("[current-artifact-path]");
        sanitized = EXECUTION_COUNTER.matcher(sanitized).replaceAll("[current-execution-count]");
        sanitized = FILE_EXTENSION.matcher(sanitized).replaceAll("[current-file]");
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        if (changed && !sanitized.toLowerCase(Locale.ROOT).contains("current")) {
            sanitized = sanitized + " Use only current task targets.";
        }
        return new SanitizedText(sanitized, changed);
    }

    public static boolean containsStatefulContent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return WINDOWS_PATH.matcher(text).find()
                || UNIX_PATH.matcher(text).find()
                || CONTEXT_REF.matcher(text).find()
                || MANIFEST_ID.matcher(text).find()
                || ARTIFACT_PATH.matcher(text).find()
                || EXECUTION_COUNTER.matcher(text).find()
                || FILE_EXTENSION.matcher(text).find();
    }

    public record SanitizedText(String text, boolean sanitized) {
    }
}

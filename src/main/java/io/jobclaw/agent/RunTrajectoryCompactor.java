package io.jobclaw.agent;

import io.jobclaw.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunTrajectoryCompactor {

    private static final Logger logger = LoggerFactory.getLogger(RunTrajectoryCompactor.class);
    private static final String SUMMARY_MARKER = "JOBCLAW_RUN_TRAJECTORY_SUMMARY";
    private static final int DEFAULT_THRESHOLD_CHARS = 80_000;
    private static final int MIN_THRESHOLD_CHARS = 24_000;
    private static final int RECENT_MESSAGES_TO_KEEP = 4;
    private static final int MAX_FACTS_PER_CATEGORY = 12;
    private static final Pattern REF_ID = Pattern.compile("(?i)\\brefId\\s*[:=]\\s*([A-Za-z0-9._:-]+)");
    private static final Pattern MANIFEST_ID = Pattern.compile("(?i)\\bmanifestId\\s*[:=]\\s*([A-Za-z0-9._:-]+)");
    private static final Pattern ARTIFACT_PATH = Pattern.compile("(?i)\\b(?:artifactPath|finalArtifactPath|output_path|Excel|JSONL)\\s*[:=：]\\s*([^\\r\\n]+)");
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\r\\n\\t\"'`<>|]+");
    private static final Pattern CONTEXT_REF_READ = Pattern.compile("(?s)Context reference:\\s*([^\\r\\n]+).*?Range:\\s*([^\\r\\n]+)");

    private final AgentConfig config;

    RunTrajectoryCompactor(AgentConfig config) {
        this.config = config;
    }

    void compactIfNeeded(List<Message> promptMessages, String sessionKey, String runId, String currentUserContent) {
        if (promptMessages == null || promptMessages.size() < 8) {
            return;
        }
        int beforeChars = totalChars(promptMessages);
        int threshold = thresholdChars();
        if (beforeChars <= threshold) {
            return;
        }

        Set<Integer> keep = protectedIndexes(promptMessages, currentUserContent);
        int recentStart = Math.max(0, promptMessages.size() - RECENT_MESSAGES_TO_KEEP);
        for (int i = recentStart; i < promptMessages.size(); i++) {
            keep.add(i);
        }

        List<Message> omitted = new ArrayList<>();
        for (int i = 0; i < promptMessages.size(); i++) {
            Message message = promptMessages.get(i);
            if (isTrajectorySummary(message)) {
                omitted.add(message);
                continue;
            }
            if (!keep.contains(i)) {
                omitted.add(message);
            }
        }
        if (omitted.isEmpty()) {
            return;
        }

        String summary = buildSummary(currentUserContent, omitted, beforeChars);
        List<Message> compacted = new ArrayList<>();
        boolean summaryInserted = false;
        for (int i = 0; i < promptMessages.size(); i++) {
            Message message = promptMessages.get(i);
            if (isTrajectorySummary(message)) {
                continue;
            }
            if (keep.contains(i)) {
                compacted.add(message);
                if (!summaryInserted && shouldInsertSummaryAfter(message, compacted.size())) {
                    compacted.add(new SystemMessage(summary));
                    summaryInserted = true;
                }
            }
        }
        if (!summaryInserted) {
            int insertAt = compacted.isEmpty() ? 0 : 1;
            compacted.add(Math.min(insertAt, compacted.size()), new SystemMessage(summary));
        }

        promptMessages.clear();
        promptMessages.addAll(compacted);
        int afterChars = totalChars(promptMessages);
        logger.info("run trajectory compacted session={} run={} messagesBefore={} messagesAfter={} charsBefore={} charsAfter={} omitted={}",
                sessionKey,
                runId,
                compacted.size() + omitted.size(),
                promptMessages.size(),
                beforeChars,
                afterChars,
                omitted.size());
    }

    private Set<Integer> protectedIndexes(List<Message> messages, String currentUserContent) {
        Set<Integer> keep = new LinkedHashSet<>();
        if (!messages.isEmpty()) {
            keep.add(0);
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String text = text(message);
            if (message instanceof SystemMessage && !isTrajectorySummary(message)) {
                keep.add(i);
            }
            if (message instanceof UserMessage
                    && currentUserContent != null
                    && !currentUserContent.isBlank()
                    && currentUserContent.equals(text)) {
                keep.add(i);
            }
        }
        return keep;
    }

    private boolean shouldInsertSummaryAfter(Message message, int compactedSize) {
        return compactedSize == 1 || message instanceof SystemMessage;
    }

    private String buildSummary(String currentUserContent, List<Message> omitted, int beforeChars) {
        SummaryFacts facts = new SummaryFacts();
        int omittedChars = 0;
        int assistantMessages = 0;
        int userMessages = 0;
        for (Message message : omitted) {
            String text = text(message);
            omittedChars += text.length();
            if (message instanceof AssistantMessage) {
                assistantMessages++;
            } else if (message instanceof UserMessage) {
                userMessages++;
            }
            facts.collect(text);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(SUMMARY_MARKER).append("\n");
        sb.append("This is a compact state card for the current run. It replaces older verbose trajectory messages only.\n");
        sb.append("Do not treat this as a new user request. Continue from the latest non-summary messages.\n\n");
        sb.append("Current user task:\n");
        sb.append("- ").append(truncate(clean(currentUserContent), 800)).append("\n\n");
        sb.append("Compacted trajectory:\n");
        sb.append("- omittedMessages: ").append(omitted.size())
                .append(" (assistant=").append(assistantMessages)
                .append(", user=").append(userMessages).append(")\n");
        sb.append("- omittedChars: ").append(omittedChars).append("\n");
        sb.append("- promptCharsBeforeCompaction: ").append(beforeChars).append("\n\n");
        appendList(sb, "Evidence refs", facts.refIds);
        appendList(sb, "Manifest ids", facts.manifestIds);
        appendList(sb, "Artifact or file paths", facts.paths);
        appendList(sb, "Recent errors or blockers", facts.errors);
        appendList(sb, "Previous context_ref reads", facts.contextReads);
        sb.append("Next action rule:\n");
        sb.append("- Use current active skill/manifest frames, current guard prompt, and latest messages as authoritative.\n");
        sb.append("- Use refId/search/summary or small-range reads for details instead of reloading large prior text.\n");
        return sb.toString();
    }

    private void appendList(StringBuilder sb, String title, Set<String> values) {
        if (values.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (String value : values) {
            sb.append("- ").append(value).append("\n");
        }
        sb.append("\n");
    }

    private int thresholdChars() {
        int threshold = DEFAULT_THRESHOLD_CHARS;
        if (config != null && config.getContextWindow() > 0) {
            int promptPercent = config.getContextMaxPromptTokenPercentage() > 0
                    ? config.getContextMaxPromptTokenPercentage()
                    : 60;
            long estimated = (long) config.getContextWindow() * promptPercent * 3L / 100L;
            threshold = (int) Math.min(DEFAULT_THRESHOLD_CHARS, Math.max(MIN_THRESHOLD_CHARS, estimated));
        }
        return threshold;
    }

    private int totalChars(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += text(message).length();
        }
        return total;
    }

    private boolean isTrajectorySummary(Message message) {
        return message instanceof SystemMessage && text(message).contains(SUMMARY_MARKER);
    }

    private String text(Message message) {
        return message != null && message.getText() != null ? message.getText() : "";
    }

    private String clean(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value != null ? value : "";
        }
        return value.substring(0, maxChars) + "...[+" + (value.length() - maxChars) + " chars]";
    }

    private final class SummaryFacts {
        private final Set<String> refIds = new LinkedHashSet<>();
        private final Set<String> manifestIds = new LinkedHashSet<>();
        private final Set<String> paths = new LinkedHashSet<>();
        private final Set<String> errors = new LinkedHashSet<>();
        private final Set<String> contextReads = new LinkedHashSet<>();

        private void collect(String text) {
            collectMatches(REF_ID, text, refIds, 1);
            collectMatches(MANIFEST_ID, text, manifestIds, 1);
            collectMatches(ARTIFACT_PATH, text, paths, 1);
            collectMatches(WINDOWS_PATH, text, paths, 0);
            collectContextReads(text);
            collectErrors(text);
        }

        private void collectMatches(Pattern pattern, String text, Set<String> target, int group) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find() && target.size() < MAX_FACTS_PER_CATEGORY) {
                target.add(truncate(clean(matcher.group(group)), 220));
            }
        }

        private void collectContextReads(String text) {
            Matcher matcher = CONTEXT_REF_READ.matcher(text);
            while (matcher.find() && contextReads.size() < MAX_FACTS_PER_CATEGORY) {
                contextReads.add("refId=" + clean(matcher.group(1)) + ", range=" + clean(matcher.group(2)));
            }
        }

        private void collectErrors(String text) {
            for (String line : text.split("\\R")) {
                String normalized = clean(line);
                String lower = normalized.toLowerCase();
                if ((lower.contains("error")
                        || lower.contains("failed")
                        || lower.contains("exception")
                        || normalized.contains("错误")
                        || normalized.contains("失败"))
                        && errors.size() < MAX_FACTS_PER_CATEGORY) {
                    errors.add(truncate(normalized, 260));
                }
            }
        }
    }
}

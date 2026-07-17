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

/** Compacts verbose tool-loop messages while preserving actionable run state. */
final class RunTrajectoryCompactor {
    static final String SUMMARY_MARKER = "JOBCLAW_RUN_TRAJECTORY_SUMMARY";
    private static final Logger logger = LoggerFactory.getLogger(RunTrajectoryCompactor.class);
    private static final int DEFAULT_THRESHOLD_CHARS = 80_000;
    private static final int MIN_THRESHOLD_CHARS = 24_000;
    private static final int RECENT_MESSAGES_TO_KEEP = 4;
    private static final int MAX_FACTS_PER_CATEGORY = 12;
    private static final Pattern REF_ID = Pattern.compile("(?i)\\brefId\\s*[:=]\\s*([A-Za-z0-9._:-]+)");
    private static final Pattern MANIFEST_ID = Pattern.compile("(?i)\\bmanifestId\\s*[:=]\\s*([A-Za-z0-9._:-]+)");
    private static final Pattern ARTIFACT_PATH = Pattern.compile("(?i)\\b(?:artifactPath|finalArtifactPath|output_path|Excel|JSONL)\\s*[:=：]\\s*([^\\r\\n]+)");
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\r\\n\\t\"'`<>|]+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?m)(?<![A-Za-z0-9._-])/(?:[^\\s\"'`<>|/]+/)*[^\\s\"'`<>|/]+");
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
        if (beforeChars <= thresholdChars()) {
            return;
        }

        Set<Integer> keep = protectedIndexes(promptMessages, currentUserContent);
        for (int i = Math.max(0, promptMessages.size() - RECENT_MESSAGES_TO_KEEP); i < promptMessages.size(); i++) {
            keep.add(i);
        }

        List<Message> omitted = new ArrayList<>();
        for (int i = 0; i < promptMessages.size(); i++) {
            Message message = promptMessages.get(i);
            if (isTrajectorySummary(message) || !keep.contains(i)) {
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
            if (isTrajectorySummary(message) || !keep.contains(i)) {
                continue;
            }
            compacted.add(message);
            if (!summaryInserted && (compacted.size() == 1 || message instanceof SystemMessage)) {
                compacted.add(new SystemMessage(summary));
                summaryInserted = true;
            }
        }
        if (!summaryInserted) {
            compacted.add(Math.min(1, compacted.size()), new SystemMessage(summary));
        }

        int beforeMessages = promptMessages.size();
        promptMessages.clear();
        promptMessages.addAll(compacted);
        logger.info("run trajectory compacted session={} run={} messagesBefore={} messagesAfter={} charsBefore={} charsAfter={} omitted={}",
                sessionKey, runId, beforeMessages, promptMessages.size(), beforeChars, totalChars(promptMessages), omitted.size());
    }

    private Set<Integer> protectedIndexes(List<Message> messages, String currentUserContent) {
        Set<Integer> keep = new LinkedHashSet<>();
        if (!messages.isEmpty()) {
            keep.add(0);
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof SystemMessage && !isTrajectorySummary(message)) {
                keep.add(i);
            }
            if (message instanceof UserMessage && currentUserContent != null && !currentUserContent.isBlank()
                    && currentUserContent.equals(text(message))) {
                keep.add(i);
            }
        }
        return keep;
    }

    private String buildSummary(String currentUserContent, List<Message> omitted, int beforeChars) {
        SummaryFacts facts = new SummaryFacts();
        int omittedChars = 0;
        int assistantMessages = 0;
        int userMessages = 0;
        for (Message message : omitted) {
            String text = text(message);
            omittedChars += text.length();
            assistantMessages += message instanceof AssistantMessage ? 1 : 0;
            userMessages += message instanceof UserMessage ? 1 : 0;
            facts.collect(text);
        }

        StringBuilder summary = new StringBuilder(SUMMARY_MARKER).append('\n')
                .append("This state card replaces older verbose messages in the current run.\n")
                .append("It is not a new user request. Continue from the latest non-summary messages.\n\n")
                .append("Current user task:\n- ").append(truncate(clean(currentUserContent), 800)).append("\n\n")
                .append("Compacted trajectory:\n")
                .append("- omittedMessages: ").append(omitted.size())
                .append(" (assistant=").append(assistantMessages).append(", user=").append(userMessages).append(")\n")
                .append("- omittedChars: ").append(omittedChars).append('\n')
                .append("- promptCharsBeforeCompaction: ").append(beforeChars).append("\n\n");
        appendList(summary, "Evidence refs", facts.refIds);
        appendList(summary, "Manifest ids", facts.manifestIds);
        appendList(summary, "Artifact or file paths", facts.paths);
        appendList(summary, "Recent errors or blockers", facts.errors);
        appendList(summary, "Previous context_ref reads", facts.contextReads);
        return summary.append("Next action rule:\n")
                .append("- Treat current skill/manifest frames, guard prompt, and latest messages as authoritative.\n")
                .append("- Use refId search, summaries, or small-range reads instead of reloading old large text.\n")
                .toString();
    }

    private void appendList(StringBuilder target, String title, Set<String> values) {
        if (values.isEmpty()) {
            return;
        }
        target.append(title).append(":\n");
        values.forEach(value -> target.append("- ").append(value).append('\n'));
        target.append('\n');
    }

    private int thresholdChars() {
        if (config == null || config.getContextWindow() <= 0) {
            return DEFAULT_THRESHOLD_CHARS;
        }
        int percentage = config.getContextMaxPromptTokenPercentage() > 0
                ? config.getContextMaxPromptTokenPercentage() : 60;
        long estimated = (long) config.getContextWindow() * percentage * 3L / 100L;
        return (int) Math.min(DEFAULT_THRESHOLD_CHARS, Math.max(MIN_THRESHOLD_CHARS, estimated));
    }

    private int totalChars(List<Message> messages) {
        return messages.stream().mapToInt(message -> text(message).length()).sum();
    }

    private boolean isTrajectorySummary(Message message) {
        return message instanceof SystemMessage && text(message).contains(SUMMARY_MARKER);
    }

    private String text(Message message) {
        return message != null && message.getText() != null ? message.getText() : "";
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
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
            collectMatches(UNIX_PATH, text, paths, 0);
            Matcher contextMatcher = CONTEXT_REF_READ.matcher(text);
            while (contextMatcher.find() && contextReads.size() < MAX_FACTS_PER_CATEGORY) {
                contextReads.add("refId=" + clean(contextMatcher.group(1)) + ", range=" + clean(contextMatcher.group(2)));
            }
            for (String line : text.split("\\R")) {
                String normalized = clean(line);
                String lower = normalized.toLowerCase();
                if ((lower.contains("error") || lower.contains("failed") || lower.contains("exception")
                        || normalized.contains("错误") || normalized.contains("失败"))
                        && errors.size() < MAX_FACTS_PER_CATEGORY) {
                    errors.add(truncate(normalized, 260));
                }
            }
        }

        private void collectMatches(Pattern pattern, String text, Set<String> target, int group) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find() && target.size() < MAX_FACTS_PER_CATEGORY) {
                target.add(truncate(clean(matcher.group(group)), 220));
            }
        }
    }
}

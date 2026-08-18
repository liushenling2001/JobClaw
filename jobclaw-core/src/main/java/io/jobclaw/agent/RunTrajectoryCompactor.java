package io.jobclaw.agent;

import io.jobclaw.config.AgentConfig;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.context.result.ResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunTrajectoryCompactor {

    private static final Logger logger = LoggerFactory.getLogger(RunTrajectoryCompactor.class);
    private static final String SUMMARY_MARKER = "JOBCLAW_RUN_TRAJECTORY_SUMMARY";
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_PROMPT_PERCENTAGE = 60;
    private static final int DEFAULT_SUMMARIZE_PERCENTAGE = 60;
    private static final int MAX_TRIGGER_TOKENS = 32_000;
    private static final int MIN_TRIGGER_TOKENS = 4_096;
    private static final int TARGET_PERCENTAGE = 60;
    private static final int MIN_RECENT_MESSAGES_TO_KEEP = 1;
    private static final int DEFAULT_RECENT_MESSAGES_TO_KEEP = 8;
    private static final int MAX_RECENT_MESSAGES_TO_KEEP = 24;
    private static final int MAX_STATE_CARD_TOKENS = 4_000;
    private static final int MAX_FACTS_PER_CATEGORY = 10;
    private static final int MAX_CHECKPOINTS = 8;
    private static final Pattern REF_ID = Pattern.compile("(?i)\\brefId\\s*[:=]\\s*([A-Za-z0-9._:-]+)");
    private static final Pattern MANIFEST_ID = Pattern.compile("(?i)\\bmanifestId\\s*[:=]\\s*([A-Za-z0-9._:-]+)");
    private static final Pattern ARTIFACT_PATH = Pattern.compile("(?i)\\b(?:artifactPath|finalArtifactPath|output_path|Excel|JSONL)\\s*[:=：]\\s*([^\\r\\n]+)");
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\r\\n\\t\"'`<>|]+");
    private static final Pattern CONTEXT_REF_READ = Pattern.compile("(?s)Context reference:\\s*([^\\r\\n]+).*?Range:\\s*([^\\r\\n]+)");

    private final AgentConfig config;
    private final ResultStore resultStore;

    RunTrajectoryCompactor(AgentConfig config) {
        this(config, new NoopResultStore());
    }

    RunTrajectoryCompactor(AgentConfig config, ResultStore resultStore) {
        this.config = config;
        this.resultStore = resultStore != null ? resultStore : new NoopResultStore();
    }

    void compactIfNeeded(List<Message> promptMessages, String sessionKey, String runId, String currentUserContent) {
        if (promptMessages == null || promptMessages.size() < 8) {
            return;
        }

        int beforeTokens = estimateMessagesTokens(promptMessages);
        int triggerTokens = triggerTokens();
        int messageThreshold = messageThreshold();
        if (beforeTokens <= triggerTokens && promptMessages.size() <= messageThreshold) {
            return;
        }

        List<MessageGroup> groups = groupMessages(promptMessages);
        Set<Integer> protectedGroups = protectedGroups(groups, currentUserContent);
        Set<Integer> retainedGroups = new LinkedHashSet<>(protectedGroups);
        addRecentGroups(groups, retainedGroups, recentMessagesToKeep());

        CompactionDraft draft = buildDraft(groups, retainedGroups, currentUserContent, beforeTokens, null);
        int targetTokens = targetTokens(triggerTokens);

        // Second stage: if the normal recent window is still too large, move the
        // oldest optional groups into the archive until the target is met.
        while (draft.estimatedTokens() > targetTokens) {
            Integer removable = oldestOptionalGroup(retainedGroups, protectedGroups, groups.size());
            if (removable == null) {
                break;
            }
            retainedGroups.remove(removable);
            draft = buildDraft(groups, retainedGroups, currentUserContent, beforeTokens, null);
        }

        if (draft.omitted().isEmpty()) {
            return;
        }

        ContextRef archiveRef = archiveTrajectory(sessionKey, runId, draft.omitted());
        draft = buildDraft(groups, retainedGroups, currentUserContent, beforeTokens, archiveRef);

        promptMessages.clear();
        promptMessages.addAll(draft.messages());
        int afterTokens = estimateMessagesTokens(promptMessages);
        if (afterTokens > triggerTokens) {
            logger.warn("run trajectory remains above trigger after compaction session={} run={} tokensAfter={} triggerTokens={} protectedGroups={}",
                    sessionKey, runId, afterTokens, triggerTokens, protectedGroups.size());
        }
        logger.info("run trajectory compacted session={} run={} messagesBefore={} messagesAfter={} tokensBefore={} tokensAfter={} triggerTokens={} targetTokens={} omitted={} archiveRef={}",
                sessionKey,
                runId,
                groups.stream().mapToInt(group -> group.messages().size()).sum(),
                promptMessages.size(),
                beforeTokens,
                afterTokens,
                triggerTokens,
                targetTokens,
                draft.omitted().size(),
                archiveRef != null ? archiveRef.getRefId() : "");
    }

    private CompactionDraft buildDraft(List<MessageGroup> groups,
                                       Set<Integer> retainedGroups,
                                       String currentUserContent,
                                       int beforeTokens,
                                       ContextRef archiveRef) {
        List<Message> omitted = new ArrayList<>();
        List<Message> retained = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            MessageGroup group = groups.get(i);
            for (Message message : group.messages()) {
                if (isTrajectorySummary(message) || !retainedGroups.contains(i)) {
                    omitted.add(message);
                } else {
                    retained.add(message);
                }
            }
        }

        String summary = buildSummary(currentUserContent, omitted, beforeTokens, archiveRef);
        List<Message> compacted = insertSummary(retained, summary);
        return new CompactionDraft(compacted, omitted, estimateMessagesTokens(compacted));
    }

    private List<Message> insertSummary(List<Message> retained, String summary) {
        List<Message> compacted = new ArrayList<>(retained.size() + 1);
        int insertAt = 0;
        while (insertAt < retained.size() && retained.get(insertAt) instanceof SystemMessage) {
            insertAt++;
        }
        compacted.addAll(retained.subList(0, insertAt));
        compacted.add(new SystemMessage(summary));
        compacted.addAll(retained.subList(insertAt, retained.size()));
        return compacted;
    }

    private List<MessageGroup> groupMessages(List<Message> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            List<Message> grouped = new ArrayList<>();
            Message message = messages.get(i);
            grouped.add(message);
            if (hasToolCalls(message)) {
                while (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                    grouped.add(messages.get(++i));
                }
            }
            groups.add(new MessageGroup(grouped));
        }
        return groups;
    }

    private Set<Integer> protectedGroups(List<MessageGroup> groups, String currentUserContent) {
        Set<Integer> keep = new LinkedHashSet<>();
        if (!groups.isEmpty()) {
            keep.add(0);
            keep.add(groups.size() - 1);
        }
        for (int i = 0; i < groups.size(); i++) {
            for (Message message : groups.get(i).messages()) {
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
        }
        return keep;
    }

    private void addRecentGroups(List<MessageGroup> groups, Set<Integer> retainedGroups, int recentMessageLimit) {
        int retainedMessages = 0;
        for (int i = groups.size() - 1; i >= 0 && retainedMessages < recentMessageLimit; i--) {
            retainedGroups.add(i);
            retainedMessages += groups.get(i).messages().size();
        }
    }

    private Integer oldestOptionalGroup(Set<Integer> retainedGroups, Set<Integer> protectedGroups, int groupCount) {
        for (int i = 0; i < groupCount; i++) {
            if (retainedGroups.contains(i) && !protectedGroups.contains(i)) {
                return i;
            }
        }
        return null;
    }

    private boolean hasToolCalls(Message message) {
        return message instanceof AssistantMessage assistantMessage
                && assistantMessage.getToolCalls() != null
                && !assistantMessage.getToolCalls().isEmpty();
    }

    private ContextRef archiveTrajectory(String sessionKey, String runId, List<Message> omitted) {
        try {
            return resultStore.save(
                    sessionKey,
                    runId,
                    "run-trajectory",
                    "context-compaction",
                    serializeTrajectory(omitted)
            );
        } catch (RuntimeException e) {
            logger.warn("failed to archive compacted run trajectory session={} run={} reason={}",
                    sessionKey, runId, e.getMessage());
            return null;
        }
    }

    private String serializeTrajectory(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("# JobClaw compacted run trajectory\n");
        sb.append("This archive is evidence from an earlier part of the same run. It is not a new task.\n\n");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            sb.append("## message ").append(i + 1)
                    .append(" role=").append(role(message))
                    .append(" type=").append(message.getClass().getSimpleName())
                    .append("\n");
            sb.append(text(message)).append("\n\n");
        }
        return sb.toString();
    }

    private String buildSummary(String currentUserContent,
                                List<Message> omitted,
                                int beforeTokens,
                                ContextRef archiveRef) {
        SummaryFacts facts = new SummaryFacts();
        int omittedTokens = 0;
        int assistantMessages = 0;
        int userMessages = 0;
        List<String> checkpoints = new ArrayList<>();
        for (Message message : omitted) {
            String text = text(message);
            omittedTokens += estimateTextTokens(text);
            if (message instanceof AssistantMessage) {
                assistantMessages++;
            } else if (message instanceof UserMessage) {
                userMessages++;
            }
            facts.collect(text);
            if (!isTrajectorySummary(message) && !text.isBlank()) {
                checkpoints.add("[" + role(message) + "] " + checkpoint(text));
            }
        }
        if (checkpoints.size() > MAX_CHECKPOINTS) {
            checkpoints = new ArrayList<>(checkpoints.subList(checkpoints.size() - MAX_CHECKPOINTS, checkpoints.size()));
        }
        Collections.reverse(checkpoints);

        StringBuilder sb = new StringBuilder();
        sb.append(SUMMARY_MARKER).append("\n");
        sb.append("This is a compact state card for the SAME current run, not a new request.\n");
        sb.append("The latest retained user/control message and active skill/manifest frames are authoritative.\n");
        sb.append("Do not repeat completed actions merely because their verbose output was archived.\n\n");
        if (archiveRef != null) {
            sb.append("Recoverable trajectory archive:\n");
            sb.append("- refId: ").append(archiveRef.getRefId()).append("\n");
            sb.append("- contentLength: ").append(archiveRef.getContentLength()).append("\n");
            sb.append("- Use context_ref search/summary or a small-range read only when details are needed.\n\n");
        }
        sb.append("Current objective:\n");
        sb.append("- ").append(truncate(clean(currentUserContent), 600)).append("\n\n");
        sb.append("Compaction facts:\n");
        sb.append("- omittedMessages: ").append(omitted.size())
                .append(" (assistant=").append(assistantMessages)
                .append(", user=").append(userMessages).append(")\n");
        sb.append("- omittedEstimatedTokens: ").append(omittedTokens).append("\n");
        sb.append("- promptEstimatedTokensBeforeCompaction: ").append(beforeTokens).append("\n\n");
        appendList(sb, "Evidence refs", facts.refIds);
        appendList(sb, "Manifest ids", facts.manifestIds);
        appendList(sb, "Artifact or file paths", facts.paths);
        appendList(sb, "Recent errors or blockers", facts.errors);
        appendList(sb, "Previous context_ref reads", facts.contextReads);
        sb.append("Continuation rules:\n");
        sb.append("- Continue from retained messages; do not restart the task or re-read completed inputs without a concrete gap.\n");
        sb.append("- Treat claimed completion as valid only when retained manifest/tool/artifact evidence supports it.\n");
        sb.append("- Retrieve archived detail through context_ref instead of restoring the full trajectory.\n");
        sb.append("\n");
        appendList(sb, "Latest compacted checkpoints (newest first)", checkpoints);
        return truncateToTokenBudget(sb.toString(), stateCardTokenBudget());
    }

    private void appendList(StringBuilder sb, String title, Iterable<String> values) {
        List<String> materialized = new ArrayList<>();
        values.forEach(materialized::add);
        if (materialized.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (String value : materialized) {
            sb.append("- ").append(value).append("\n");
        }
        sb.append("\n");
    }

    private int triggerTokens() {
        int contextWindow = config != null && config.getContextWindow() > 0
                ? config.getContextWindow()
                : DEFAULT_CONTEXT_WINDOW;
        int promptPercentage = percentage(
                config != null ? config.getContextMaxPromptTokenPercentage() : 0,
                DEFAULT_PROMPT_PERCENTAGE
        );
        int summarizePercentage = percentage(
                config != null ? config.getSummarizeTokenPercentage() : 0,
                DEFAULT_SUMMARIZE_PERCENTAGE
        );
        long promptBudget = (long) contextWindow * promptPercentage / 100L;
        long summarizeBudget = (long) contextWindow * summarizePercentage / 100L;
        long configuredBudget = Math.min(promptBudget, summarizeBudget);
        return (int) Math.min(MAX_TRIGGER_TOKENS, Math.max(MIN_TRIGGER_TOKENS, configuredBudget));
    }

    private int targetTokens(int triggerTokens) {
        return Math.max(2_048, triggerTokens * TARGET_PERCENTAGE / 100);
    }

    private int stateCardTokenBudget() {
        return Math.min(MAX_STATE_CARD_TOKENS, Math.max(1_024, targetTokens(triggerTokens()) / 3));
    }

    private int messageThreshold() {
        return config != null && config.getSummarizeMessageThreshold() > 0
                ? Math.max(8, config.getSummarizeMessageThreshold())
                : 200;
    }

    private int recentMessagesToKeep() {
        int configured = config != null && config.getRecentMessagesToKeep() > 0
                ? config.getRecentMessagesToKeep()
                : DEFAULT_RECENT_MESSAGES_TO_KEEP;
        return Math.min(MAX_RECENT_MESSAGES_TO_KEEP, Math.max(MIN_RECENT_MESSAGES_TO_KEEP, configured));
    }

    private int percentage(int configured, int fallback) {
        return configured > 0 && configured <= 100 ? configured : fallback;
    }

    private int estimateMessagesTokens(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            total += estimateTextTokens(text(message)) + 8L;
        }
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private int estimateTextTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if ((c >= '\u3400' && c <= '\u4dbf')
                    || (c >= '\u4e00' && c <= '\u9fff')
                    || (c >= '\uf900' && c <= '\ufaff')) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        return cjkChars + (otherChars + 3) / 4;
    }

    private String truncateToTokenBudget(String value, int tokenBudget) {
        if (value == null || estimateTextTokens(value) <= tokenBudget) {
            return value != null ? value : "";
        }
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (estimateTextTokens(value.substring(0, middle)) <= Math.max(1, tokenBudget - 24)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, low) + "\n[State card truncated; use its trajectory refId for omitted detail.]";
    }

    private boolean isTrajectorySummary(Message message) {
        return message instanceof SystemMessage && text(message).contains(SUMMARY_MARKER);
    }

    private String role(Message message) {
        if (message instanceof SystemMessage) {
            return "system";
        }
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof ToolResponseMessage) {
            return "tool";
        }
        if (message instanceof UserMessage) {
            return "user";
        }
        return message != null && message.getMessageType() != null
                ? message.getMessageType().getValue()
                : "unknown";
    }

    private String text(Message message) {
        return message != null && message.getText() != null ? message.getText() : "";
    }

    private String clean(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
    }

    private String checkpoint(String value) {
        String cleaned = clean(value);
        if (cleaned.length() <= 600) {
            return cleaned;
        }
        return cleaned.substring(0, 360)
                + " ...[middle omitted]... "
                + cleaned.substring(cleaned.length() - 180);
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value != null ? value : "";
        }
        return value.substring(0, maxChars) + "...[+" + (value.length() - maxChars) + " chars]";
    }

    private record MessageGroup(List<Message> messages) {
    }

    private record CompactionDraft(List<Message> messages, List<Message> omitted, int estimatedTokens) {
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

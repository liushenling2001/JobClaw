package io.jobclaw.context;

import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.providers.Message;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.retrieval.SearchQuery;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DefaultContextAssembler implements ContextAssembler {

    private static final int DEFAULT_MAX_PROMPT_TOKENS = 32_768;
    private static final int SUMMARY_BUDGET_DIVISOR = 5;
    private static final int MEMORY_BUDGET_DIVISOR = 6;
    private static final int RETRIEVED_SUMMARY_BUDGET_DIVISOR = 6;

    private final SessionManager sessionManager;
    private final int defaultRecentLimit;
    private final RetrievalService retrievalService;

    public DefaultContextAssembler(SessionManager sessionManager, int defaultRecentLimit, RetrievalService retrievalService) {
        this.sessionManager = sessionManager;
        this.defaultRecentLimit = defaultRecentLimit;
        this.retrievalService = retrievalService;
    }

    @Override
    public List<Message> assemble(String sessionId, String currentUserInput, ContextAssemblyOptions options) {
        List<Message> history = new ArrayList<>(sessionManager.getHistory(sessionId));
        int recentLimit = options != null && options.recentMessageLimit() > 0
                ? options.recentMessageLimit()
                : defaultRecentLimit;

        if (recentLimit > 0 && history.size() > recentLimit) {
            history = new ArrayList<>(history.subList(history.size() - recentLimit, history.size()));
        }

        if (!history.isEmpty()) {
            Message last = history.get(history.size() - 1);
            if ("user".equals(last.getRole()) && currentUserInput != null && currentUserInput.equals(last.getContent())) {
                history.remove(history.size() - 1);
            }
        }

        List<Message> assembled = new ArrayList<>();
        int maxPromptTokens = options != null && options.maxPromptTokens() > 0
                ? options.maxPromptTokens()
                : DEFAULT_MAX_PROMPT_TOKENS;
        int summaryBudget = Math.max(256, maxPromptTokens / SUMMARY_BUDGET_DIVISOR);
        int memoryBudget = Math.max(192, maxPromptTokens / MEMORY_BUDGET_DIVISOR);
        int retrievedSummaryBudget = Math.max(192, maxPromptTokens / RETRIEVED_SUMMARY_BUDGET_DIVISOR);
        int historyBudget = Math.max(512, maxPromptTokens - summaryBudget - memoryBudget - retrievedSummaryBudget);
        int summaryLimit = options != null && options.retrievedSummaryLimit() > 0 ? options.retrievedSummaryLimit() : 3;
        int historyRetrievalLimit = options != null && options.retrievedHistoryLimit() > 0 ? options.retrievedHistoryLimit() : 6;
        int memoryLimit = options != null && options.retrievedMemoryLimit() > 0 ? options.retrievedMemoryLimit() : 6;

        SearchQuery summaryQuery = new SearchQuery(sessionId, currentUserInput, null, null, null, summaryLimit, null);
        SearchQuery historyQuery = new SearchQuery(sessionId, currentUserInput, null, null, null, historyRetrievalLimit, null);
        SearchQuery memoryQuery = new SearchQuery(sessionId, currentUserInput, null, null, null, memoryLimit, null);

        List<ChunkSummary> chunkSummaries = retrievalService.searchSummaries(summaryQuery);
        List<StoredMessage> retrievedHistory = retrievalService.searchHistory(historyQuery);
        List<MemoryFact> memoryFacts = retrievalService.searchMemory(memoryQuery);
        Optional<SessionSummaryRecord> sessionSummary = retrievalService.getSessionSummary(sessionId);

        sessionSummary
                .filter(summary -> summary.summaryText() != null && !summary.summaryText().isBlank())
                .ifPresent(summary -> assembled.add(Message.system(
                        "Session summary:\n" + truncateToBudget(summary.summaryText(), summaryBudget)
                )));

        if (!chunkSummaries.isEmpty()) {
            int fromIndex = Math.max(0, chunkSummaries.size() - summaryLimit);
            List<ChunkSummary> limitedChunkSummaries = chunkSummaries.subList(fromIndex, chunkSummaries.size());
            int perSummaryBudget = Math.max(96, retrievedSummaryBudget / Math.max(1, limitedChunkSummaries.size()));
            limitedChunkSummaries
                    .forEach(chunkSummary -> assembled.add(Message.system(
                            "Relevant prior summary [" + chunkSummary.chunkId() + "]:\n"
                                    + truncateToBudget(chunkSummary.summaryText(), perSummaryBudget)
                    )));
        }

        if (!memoryFacts.isEmpty()) {
            assembled.add(Message.system(buildMemoryFactContext(memoryFacts, memoryBudget)));
        }

        appendRetrievedHistory(assembled, retrievedHistory, history, historyBudget / 3);

        history = trimRecentHistoryToBudget(history, Math.max(256, historyBudget - estimateMessagesTokens(assembled)));

        assembled.addAll(history);
        return assembled;
    }

    private void appendRetrievedHistory(List<Message> assembled,
                                        List<StoredMessage> retrievedHistory,
                                        List<Message> recentHistory,
                                        int historyBudget) {
        if (retrievedHistory == null || retrievedHistory.isEmpty()) {
            return;
        }

        int consumed = 0;
        for (StoredMessage storedMessage : retrievedHistory) {
            if (isAlreadyPresent(storedMessage, recentHistory)) {
                continue;
            }
            Message converted = toProviderMessage(storedMessage);
            if (converted != null) {
                int messageTokens = estimateMessageTokens(converted);
                if (consumed + messageTokens > historyBudget) {
                    break;
                }
                assembled.add(converted);
                consumed += messageTokens;
            }
        }
    }

    private boolean isAlreadyPresent(StoredMessage storedMessage, List<Message> recentHistory) {
        for (Message message : recentHistory) {
            if (message == null) {
                continue;
            }
            if (equalsNullable(storedMessage.role(), message.getRole())
                    && equalsNullable(storedMessage.content(), message.getContent())) {
                return true;
            }
        }
        return false;
    }

    private Message toProviderMessage(StoredMessage storedMessage) {
        if (storedMessage == null || storedMessage.role() == null) {
            return null;
        }
        return switch (storedMessage.role()) {
            case "system" -> Message.system(storedMessage.content());
            case "assistant" -> Message.assistant(storedMessage.content());
            case "tool" -> Message.tool(storedMessage.toolCallId(), storedMessage.content());
            default -> Message.user(storedMessage.content());
        };
    }

    private String buildMemoryFactContext(List<MemoryFact> memoryFacts, int tokenBudget) {
        StringBuilder builder = new StringBuilder("Relevant memory facts:\n");
        int consumed = estimateToken(builder.toString());
        for (MemoryFact fact : memoryFacts.stream().limit(6).toList()) {
            String line = "- " + fact.factType() + ": " + fact.objectText() + '\n';
            int lineTokens = estimateToken(line);
            if (consumed + lineTokens > tokenBudget) {
                break;
            }
            builder.append(line);
            consumed += lineTokens;
        }
        return builder.toString();
    }

    private List<Message> trimRecentHistoryToBudget(List<Message> history, int historyBudget) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<Message> trimmed = new ArrayList<>();
        int consumed = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            int messageTokens = estimateMessageTokens(message);
            if (!trimmed.isEmpty() && consumed + messageTokens > historyBudget) {
                break;
            }
            trimmed.add(0, truncateMessageToBudget(message, Math.min(messageTokens, historyBudget - consumed)));
            consumed += estimateMessageTokens(trimmed.get(0));
        }
        return trimmed;
    }

    private Message truncateMessageToBudget(Message message, int budget) {
        if (message == null || message.getContent() == null) {
            return message;
        }
        String truncated = truncateToBudget(message.getContent(), budget);
        return switch (message.getRole()) {
            case "system" -> Message.system(truncated);
            case "assistant" -> Message.assistant(truncated);
            case "tool" -> Message.tool(message.getToolCallId(), truncated);
            default -> Message.user(truncated);
        };
    }

    private String truncateToBudget(String text, int tokenBudget) {
        if (text == null || text.isBlank() || tokenBudget <= 0) {
            return "";
        }
        if (estimateToken(text) <= tokenBudget) {
            return text;
        }

        int maxChars = Math.max(64, tokenBudget * 4);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[truncated]";
    }

    private int estimateMessagesTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateMessageTokens(message);
        }
        return total;
    }

    private int estimateMessageTokens(Message message) {
        if (message == null) {
            return 0;
        }
        return estimateToken(message.getRole()) + estimateToken(message.getContent()) + 8;
    }

    private int estimateToken(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int englishChars = 0;
        for (char c : content.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                englishChars++;
            }
        }
        return chineseChars + englishChars / 4;
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

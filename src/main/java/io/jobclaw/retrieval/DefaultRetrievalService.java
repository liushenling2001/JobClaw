package io.jobclaw.retrieval;

import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.SessionRecord;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public class DefaultRetrievalService implements RetrievalService {

    private final ConversationStore conversationStore;
    private final SummaryService summaryService;

    public DefaultRetrievalService(ConversationStore conversationStore, SummaryService summaryService) {
        this.conversationStore = conversationStore;
        this.summaryService = summaryService;
    }

    @Override
    public List<StoredMessage> searchHistory(SearchQuery query) {
        if (query == null) {
            return List.of();
        }

        return streamSessions(query.sessionId())
                .flatMap(session -> conversationStore.listMessages(
                        session.getSessionId(),
                        0,
                        Integer.MAX_VALUE
                ).stream())
                .filter(message -> matchesTimeRange(message.createdAt(), query.from(), query.to()))
                .filter(message -> query.role() == null || query.role().isBlank() || query.role().equals(message.role()))
                .filter(message -> matchesText(message.content(), query.queryText()))
                .sorted(Comparator
                        .comparingInt((StoredMessage message) -> scoreText(message.content(), query.queryText()))
                        .reversed()
                        .thenComparing(StoredMessage::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(normalizeLimit(query.limit(), 10))
                .toList();
    }

    @Override
    public List<ChunkSummary> searchSummaries(SearchQuery query) {
        if (query == null) {
            return List.of();
        }

        return streamSessions(query.sessionId())
                .flatMap(session -> summaryService.listChunkSummaries(session.getSessionId()).stream())
                .filter(summary -> matchesText(summary.summaryText(), query.queryText()))
                .sorted(Comparator
                        .comparingInt((ChunkSummary summary) -> scoreText(summary.summaryText(), query.queryText()))
                        .reversed()
                        .thenComparing(ChunkSummary::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(normalizeLimit(query.limit(), 6))
                .toList();
    }

    @Override
    public List<MemoryFact> searchMemory(SearchQuery query) {
        if (query == null) {
            return List.of();
        }

        return streamSessions(query.sessionId())
                .flatMap(session -> summaryService.listMemoryFacts(session.getSessionId()).stream())
                .filter(fact -> fact.active())
                .filter(fact -> query.role() == null || query.role().isBlank()
                        || query.role().equals(fact.scope()) || query.role().equals(fact.factType()))
                .filter(fact -> matchesText(fact.objectText(), query.queryText())
                        || matchesText(fact.subject(), query.queryText())
                        || matchesText(fact.predicate(), query.queryText()))
                .sorted(Comparator
                        .comparingDouble((MemoryFact fact) -> fact.confidence() + scoreText(fact.objectText(), query.queryText()))
                        .reversed()
                        .thenComparing(MemoryFact::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(normalizeLimit(query.limit(), 8))
                .toList();
    }

    @Override
    public Optional<SessionSummaryRecord> getSessionSummary(String sessionId) {
        return summaryService.getSessionSummary(sessionId);
    }

    @Override
    public RetrievalBundle retrieveForContext(String sessionId, String userInput) {
        SearchQuery historyQuery = new SearchQuery(sessionId, userInput, null, null, null, 6, null);
        SearchQuery summaryQuery = new SearchQuery(sessionId, userInput, null, null, null, 4, null);
        SearchQuery memoryQuery = new SearchQuery(sessionId, userInput, null, null, null, 8, null);

        return new RetrievalBundle(
                searchHistory(historyQuery),
                searchSummaries(summaryQuery),
                searchMemory(memoryQuery),
                getSessionSummary(sessionId)
        );
    }

    private Stream<SessionRecord> streamSessions(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return conversationStore.getSession(sessionId)
                    .stream();
        }
        return conversationStore.listSessions().stream();
    }

    private boolean matchesText(String content, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return true;
        }
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        for (String term : tokenize(queryText)) {
            if (normalizedContent.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private int scoreText(String content, String queryText) {
        if (queryText == null || queryText.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        int score = 0;
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        for (String term : tokenize(queryText)) {
            if (normalizedContent.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> tokenize(String queryText) {
        List<String> terms = new ArrayList<>();
        for (String token : queryText.toLowerCase(Locale.ROOT).split("\\s+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                terms.add(trimmed);
            }
        }
        return terms.isEmpty() ? List.of(queryText.toLowerCase(Locale.ROOT)) : terms;
    }

    private boolean matchesTimeRange(Instant createdAt, Instant from, Instant to) {
        if (createdAt == null) {
            return from == null && to == null;
        }
        if (from != null && createdAt.isBefore(from)) {
            return false;
        }
        if (to != null && createdAt.isAfter(to)) {
            return false;
        }
        return true;
    }

    private int normalizeLimit(int limit, int defaultLimit) {
        return limit > 0 ? limit : defaultLimit;
    }
}

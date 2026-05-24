package io.jobclaw.retrieval;

import io.jobclaw.conversation.ConversationStore;
import io.jobclaw.conversation.SessionRecord;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqliteRetrievalService implements RetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(SqliteRetrievalService.class);
    private static final String SQLITE_TMPDIR_PROPERTY = "org.sqlite.tmpdir";

    private final ConversationStore conversationStore;
    private final SummaryService summaryService;
    private final String jdbcUrl;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final RetrievalService fallbackService;

    public SqliteRetrievalService(ConversationStore conversationStore,
                                  SummaryService summaryService,
                                  String databasePath) {
        this.conversationStore = conversationStore;
        this.summaryService = summaryService;
        this.fallbackService = new DefaultRetrievalService(conversationStore, summaryService);
        Path dbPath = databasePath == null || databasePath.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "sessions", "conversation", "search.db")
                : Paths.get(databasePath);
        configureSqliteNativeTempDir();
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try {
            Files.createDirectories(dbPath.toAbsolutePath().getParent());
        } catch (Exception e) {
            logger.warn("Failed to create sqlite directory: {}", e.getMessage());
        }
    }

    private void configureSqliteNativeTempDir() {
        if (System.getProperty(SQLITE_TMPDIR_PROPERTY) != null
                && !System.getProperty(SQLITE_TMPDIR_PROPERTY).isBlank()) {
            return;
        }
        Path nativeTempDir = Paths.get(System.getProperty("user.home"), ".jobclaw", "native", "sqlite");
        try {
            Files.createDirectories(nativeTempDir);
            System.setProperty(SQLITE_TMPDIR_PROPERTY, nativeTempDir.toString());
        } catch (Exception e) {
            logger.warn("Failed to configure sqlite native temp directory: {}", e.getMessage());
        }
    }

    @Override
    public List<StoredMessage> searchHistory(SearchQuery query) {
        try {
            ensureReady();
            syncRelevantSessions(query != null ? query.sessionId() : null);
            return searchHistoryInternal(query);
        } catch (Exception e) {
            logger.warn("Falling back to in-memory history search: {}", e.getMessage());
            return fallbackService.searchHistory(query);
        }
    }

    @Override
    public List<ChunkSummary> searchSummaries(SearchQuery query) {
        try {
            ensureReady();
            syncRelevantSessions(query != null ? query.sessionId() : null);
            return searchSummariesInternal(query);
        } catch (Exception e) {
            logger.warn("Falling back to file summary search: {}", e.getMessage());
            return fallbackService.searchSummaries(query);
        }
    }

    @Override
    public List<MemoryFact> searchMemory(SearchQuery query) {
        try {
            ensureReady();
            syncRelevantSessions(query != null ? query.sessionId() : null);
            return searchMemoryInternal(query);
        } catch (Exception e) {
            logger.warn("Falling back to file memory search: {}", e.getMessage());
            return fallbackService.searchMemory(query);
        }
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

    private void ensureReady() throws SQLException {
        if (schemaReady.get()) {
            return;
            }
            synchronized (schemaReady) {
                if (schemaReady.get()) {
                    return;
                }
                try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE IF EXISTS message_fts");
                statement.executeUpdate("DROP TABLE IF EXISTS chunk_summary_fts");
                statement.executeUpdate("DROP TABLE IF EXISTS memory_fact_fts");
                statement.executeUpdate("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            message_id UNINDEXED,
                            session_id UNINDEXED,
                            role UNINDEXED,
                            content,
                            created_at UNINDEXED
                        )
                        """);
                statement.executeUpdate("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS chunk_summary_fts USING fts5(
                            chunk_id UNINDEXED,
                            session_id UNINDEXED,
                            summary_text UNINDEXED,
                            searchable_text,
                            created_at UNINDEXED
                        )
                        """);
                statement.executeUpdate("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS memory_fact_fts USING fts5(
                            fact_id UNINDEXED,
                            session_id UNINDEXED,
                            fact_type UNINDEXED,
                            subject UNINDEXED,
                            predicate UNINDEXED,
                            object_text UNINDEXED,
                            searchable_text,
                            updated_at UNINDEXED,
                            active UNINDEXED,
                            confidence UNINDEXED
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS sync_state (
                            session_id TEXT PRIMARY KEY,
                            last_message_at TEXT,
                            last_summary_at TEXT,
                            last_memory_at TEXT,
                            synced_at TEXT
                        )
                        """);
            }
            schemaReady.set(true);
        }
    }

    private void syncRelevantSessions(String sessionId) throws SQLException {
        if (sessionId != null && !sessionId.isBlank()) {
            syncSession(sessionId);
            return;
        }
        for (SessionRecord sessionRecord : conversationStore.listSessions()) {
            syncSession(sessionRecord.getSessionId());
        }
    }

    private void syncSession(String sessionId) throws SQLException {
        if (!needsSync(sessionId)) {
            return;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            deleteSessionRows(connection, "message_fts", sessionId);
            deleteSessionRows(connection, "chunk_summary_fts", sessionId);
            deleteSessionRows(connection, "memory_fact_fts", sessionId);
            insertMessages(connection, sessionId);
            insertChunkSummaries(connection, sessionId);
            insertMemoryFacts(connection, sessionId);
            updateSyncState(connection, sessionId);
            connection.commit();
        }
    }

    private void deleteSessionRows(Connection connection, String table, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    private void insertMessages(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO message_fts(message_id, session_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)")) {
            for (StoredMessage message : conversationStore.listMessages(sessionId, 0, Integer.MAX_VALUE)) {
                statement.setString(1, message.messageId());
                statement.setString(2, message.sessionId());
                statement.setString(3, message.role());
                statement.setString(4, safeText(message.content()));
                statement.setString(5, message.createdAt() != null ? message.createdAt().toString() : null);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertChunkSummaries(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chunk_summary_fts(chunk_id, session_id, summary_text, searchable_text, created_at) VALUES (?, ?, ?, ?, ?)")) {
            for (ChunkSummary summary : summaryService.listChunkSummaries(sessionId)) {
                statement.setString(1, summary.chunkId());
                statement.setString(2, summary.sessionId());
                statement.setString(3, safeText(summary.summaryText()));
                statement.setString(4, buildChunkSummarySearchText(summary));
                statement.setString(5, summary.createdAt() != null ? summary.createdAt().toString() : null);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertMemoryFacts(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO memory_fact_fts(fact_id, session_id, fact_type, subject, predicate, object_text, searchable_text, updated_at, active, confidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (MemoryFact fact : summaryService.listMemoryFacts(sessionId)) {
                statement.setString(1, fact.factId());
                statement.setString(2, fact.sessionId());
                statement.setString(3, fact.factType());
                statement.setString(4, safeText(fact.subject()));
                statement.setString(5, safeText(fact.predicate()));
                statement.setString(6, safeText(fact.objectText()));
                statement.setString(7, buildMemoryFactSearchText(fact));
                statement.setString(8, fact.updatedAt() != null ? fact.updatedAt().toString() : null);
                statement.setString(9, fact.active() ? "1" : "0");
                statement.setDouble(10, fact.confidence());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<StoredMessage> searchHistoryInternal(SearchQuery query) throws SQLException {
        String ftsQuery = toFtsQuery(query != null ? query.queryText() : null);
        String sql = ftsQuery == null
                ? """
                SELECT message_id, session_id, role, content, created_at
                FROM message_fts
                WHERE (? IS NULL OR session_id = ?)
                  AND (? IS NULL OR role = ?)
                  AND (? IS NULL OR created_at >= ?)
                  AND (? IS NULL OR created_at <= ?)
                ORDER BY created_at DESC
                LIMIT ?
                """
                : """
                SELECT message_id, session_id, role, content, created_at
                FROM message_fts
                WHERE (? IS NULL OR session_id = ?)
                  AND (? IS NULL OR role = ?)
                  AND (? IS NULL OR created_at >= ?)
                  AND (? IS NULL OR created_at <= ?)
                  AND message_fts MATCH ?
                ORDER BY bm25(message_fts), created_at DESC
                LIMIT ?
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindCommonFilters(statement, query);
            if (ftsQuery != null) {
                statement.setString(index++, ftsQuery);
            }
            statement.setInt(index, normalizeLimit(query, 10));
            try (ResultSet rs = statement.executeQuery()) {
                List<StoredMessage> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new StoredMessage(
                            rs.getString("message_id"),
                            rs.getString("session_id"),
                            0,
                            rs.getString("role"),
                            rs.getString("content"),
                            null,
                            null,
                            null,
                            null,
                            java.util.Map.of(),
                            parseInstant(rs.getString("created_at"))
                    ));
                }
                return results;
            }
        }
    }

    private List<ChunkSummary> searchSummariesInternal(SearchQuery query) throws SQLException {
        String ftsQuery = toFtsQuery(query != null ? query.queryText() : null);
        String sql = ftsQuery == null
                ? """
                SELECT chunk_id, session_id, summary_text, created_at
                FROM chunk_summary_fts
                WHERE (? IS NULL OR session_id = ?)
                  AND (? IS NULL OR created_at >= ?)
                  AND (? IS NULL OR created_at <= ?)
                ORDER BY created_at DESC
                LIMIT ?
                """
                : """
                SELECT chunk_id, session_id, summary_text, created_at
                FROM chunk_summary_fts
                WHERE (? IS NULL OR session_id = ?)
                  AND (? IS NULL OR created_at >= ?)
                  AND (? IS NULL OR created_at <= ?)
                  AND chunk_summary_fts MATCH ?
                ORDER BY bm25(chunk_summary_fts), created_at DESC
                LIMIT ?
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindSessionAndTimeRange(statement, query, 1, "created_at");
            if (ftsQuery != null) {
                statement.setString(index++, ftsQuery);
            }
            statement.setInt(index, normalizeLimit(query, 6));
            try (ResultSet rs = statement.executeQuery()) {
                List<ChunkSummary> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new ChunkSummary(
                            rs.getString("chunk_id"),
                            rs.getString("session_id"),
                            rs.getString("summary_text"),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            1,
                            parseInstant(rs.getString("created_at"))
                    ));
                }
                return results;
            }
        }
    }

    private List<MemoryFact> searchMemoryInternal(SearchQuery query) throws SQLException {
        String ftsQuery = toFtsQuery(query != null ? query.queryText() : null);
        String sql = ftsQuery == null
                ? """
                SELECT fact_id, session_id, fact_type, subject, predicate, object_text, updated_at, active, confidence
                FROM memory_fact_fts
                WHERE (? IS NULL OR session_id = ?)
                  AND (? IS NULL OR fact_type = ?)
                  AND (? IS NULL OR updated_at >= ?)
                  AND (? IS NULL OR updated_at <= ?)
                  AND active = '1'
                ORDER BY confidence DESC, updated_at DESC
                LIMIT ?
                """
                : """
                SELECT fact_id, session_id, fact_type, subject, predicate, object_text, updated_at, active, confidence
                FROM memory_fact_fts
                WHERE (? IS NULL OR session_id = ?)
                  AND (? IS NULL OR fact_type = ?)
                  AND (? IS NULL OR updated_at >= ?)
                  AND (? IS NULL OR updated_at <= ?)
                  AND active = '1'
                  AND memory_fact_fts MATCH ?
                ORDER BY bm25(memory_fact_fts), confidence DESC, updated_at DESC
                LIMIT ?
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindSessionRoleAndTimeRange(statement, query, 1, "updated_at", "fact_type");
            if (ftsQuery != null) {
                statement.setString(index++, ftsQuery);
            }
            statement.setInt(index, normalizeLimit(query, 8));
            try (ResultSet rs = statement.executeQuery()) {
                List<MemoryFact> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new MemoryFact(
                            rs.getString("fact_id"),
                            rs.getString("session_id"),
                            "session",
                            rs.getString("fact_type"),
                            rs.getString("subject"),
                            rs.getString("predicate"),
                            rs.getString("object_text"),
                            java.util.Map.of(),
                            rs.getDouble("confidence"),
                            "1".equals(rs.getString("active")),
                            parseInstant(rs.getString("updated_at")),
                            parseInstant(rs.getString("updated_at"))
                    ));
                }
                return results;
            }
        }
    }

    private int bindCommonFilters(PreparedStatement statement, SearchQuery query) throws SQLException {
        int index = bindSessionRoleAndTimeRange(statement, query, 1, "created_at", "role");
        return index;
    }

    private int bindSessionRoleAndTimeRange(PreparedStatement statement,
                                            SearchQuery query,
                                            int startIndex,
                                            String timeColumn,
                                            String roleColumn) throws SQLException {
        int index = bindSession(statement, query, startIndex);
        index = bindOptionalValue(statement, index, query != null ? query.role() : null);
        return bindTimeRange(statement, query, index);
    }

    private int bindSessionAndTimeRange(PreparedStatement statement,
                                        SearchQuery query,
                                        int startIndex,
                                        String timeColumn) throws SQLException {
        int index = bindSession(statement, query, startIndex);
        return bindTimeRange(statement, query, index);
    }

    private int bindSession(PreparedStatement statement, SearchQuery query, int startIndex) throws SQLException {
        String sessionId = query != null ? query.sessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            statement.setString(startIndex, null);
            statement.setString(startIndex + 1, null);
        } else {
            statement.setString(startIndex, sessionId);
            statement.setString(startIndex + 1, sessionId);
        }
        return startIndex + 2;
    }

    private int bindTimeRange(PreparedStatement statement, SearchQuery query, int startIndex) throws SQLException {
        String from = query != null && query.from() != null ? query.from().toString() : null;
        String to = query != null && query.to() != null ? query.to().toString() : null;
        int index = bindOptionalValue(statement, startIndex, from);
        return bindOptionalValue(statement, index, to);
    }

    private int bindOptionalValue(PreparedStatement statement, int startIndex, String value) throws SQLException {
        statement.setString(startIndex, value);
        statement.setString(startIndex + 1, value);
        return startIndex + 2;
    }

    private String toFtsQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (String token : queryText.toLowerCase(Locale.ROOT).split("\\s+")) {
            String normalized = token.replace("\"", "").trim();
            if (!normalized.isEmpty()) {
                tokens.add("\"" + normalized + "\"*");
            }
        }
        if (tokens.isEmpty()) {
            String normalized = queryText.toLowerCase(Locale.ROOT).replace("\"", "").trim();
            return normalized.isEmpty() ? null : "\"" + normalized + "\"*";
        }
        return String.join(" OR ", tokens);
    }

    private int normalizeLimit(SearchQuery query, int defaultValue) {
        return query != null && query.limit() > 0 ? query.limit() : defaultValue;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }

    private boolean needsSync(String sessionId) throws SQLException {
        Instant sourceMessageAt = conversationStore.getSession(sessionId)
                .map(SessionRecord::getLastMessageAt)
                .orElse(null);
        Instant sourceSummaryAt = summaryService.listChunkSummaries(sessionId).stream()
                .map(ChunkSummary::createdAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        Instant sourceMemoryAt = summaryService.listMemoryFacts(sessionId).stream()
                .map(MemoryFact::updatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_message_at, last_summary_at, last_memory_at FROM sync_state WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                return !instantEquals(sourceMessageAt, parseInstant(rs.getString("last_message_at")))
                        || !instantEquals(sourceSummaryAt, parseInstant(rs.getString("last_summary_at")))
                        || !instantEquals(sourceMemoryAt, parseInstant(rs.getString("last_memory_at")));
            }
        }
    }

    private void updateSyncState(Connection connection, String sessionId) throws SQLException {
        Instant sourceMessageAt = conversationStore.getSession(sessionId)
                .map(SessionRecord::getLastMessageAt)
                .orElse(null);
        Instant sourceSummaryAt = summaryService.listChunkSummaries(sessionId).stream()
                .map(ChunkSummary::createdAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        Instant sourceMemoryAt = summaryService.listMemoryFacts(sessionId).stream()
                .map(MemoryFact::updatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sync_state(session_id, last_message_at, last_summary_at, last_memory_at, synced_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    last_message_at = excluded.last_message_at,
                    last_summary_at = excluded.last_summary_at,
                    last_memory_at = excluded.last_memory_at,
                    synced_at = excluded.synced_at
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, sourceMessageAt != null ? sourceMessageAt.toString() : null);
            statement.setString(3, sourceSummaryAt != null ? sourceSummaryAt.toString() : null);
            statement.setString(4, sourceMemoryAt != null ? sourceMemoryAt.toString() : null);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private boolean instantEquals(Instant left, Instant right) {
        return left == null ? right == null : left.equals(right);
    }

    private String buildChunkSummarySearchText(ChunkSummary summary) {
        List<String> parts = new ArrayList<>();
        parts.add(safeText(summary.summaryText()));
        parts.add(String.join(" ", summary.entities()));
        parts.add(String.join(" ", summary.topics()));
        parts.add(String.join(" ", summary.decisions()));
        parts.add(String.join(" ", summary.openQuestions()));
        return String.join(" ", parts).trim();
    }

    private String buildMemoryFactSearchText(MemoryFact fact) {
        List<String> parts = new ArrayList<>();
        parts.add(safeText(fact.factType()));
        parts.add(safeText(fact.scope()));
        parts.add(safeText(fact.subject()));
        parts.add(safeText(fact.predicate()));
        parts.add(safeText(fact.objectText()));
        return String.join(" ", parts).trim();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}

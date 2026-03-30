package io.jobclaw.agent.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SqliteAgentCatalogStore implements AgentCatalogStore {

    private static final Logger logger = LoggerFactory.getLogger(SqliteAgentCatalogStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final String jdbcUrl;

    public SqliteAgentCatalogStore(String dbPath) {
        Path path = dbPath == null || dbPath.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "sessions", "agents.db")
                : Paths.get(dbPath);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize agent catalog db path {}: {}", path, e.getMessage());
        }
        this.jdbcUrl = "jdbc:sqlite:" + path;
        initSchema();
    }

    @Override
    public AgentCatalogEntry save(AgentCatalogEntry entry) {
        String sql = """
                INSERT INTO agents (
                    agent_id, code, display_name, description, system_prompt, aliases_json,
                    allowed_tools_json, allowed_skills_json, model_config_json, memory_scope,
                    status, visibility, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(agent_id) DO UPDATE SET
                    code = excluded.code,
                    display_name = excluded.display_name,
                    description = excluded.description,
                    system_prompt = excluded.system_prompt,
                    aliases_json = excluded.aliases_json,
                    allowed_tools_json = excluded.allowed_tools_json,
                    allowed_skills_json = excluded.allowed_skills_json,
                    model_config_json = excluded.model_config_json,
                    memory_scope = excluded.memory_scope,
                    status = excluded.status,
                    visibility = excluded.visibility,
                    version = excluded.version,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.agentId());
            statement.setString(2, entry.code());
            statement.setString(3, entry.displayName());
            statement.setString(4, entry.description());
            statement.setString(5, entry.systemPrompt());
            statement.setString(6, toJsonList(entry.aliases()));
            statement.setString(7, toJsonList(entry.allowedTools()));
            statement.setString(8, toJsonList(entry.allowedSkills()));
            statement.setString(9, toJsonMap(entry.modelConfig()));
            statement.setString(10, entry.memoryScope());
            statement.setString(11, entry.status());
            statement.setString(12, entry.visibility());
            statement.setInt(13, entry.version());
            statement.setTimestamp(14, Timestamp.from(entry.createdAt()));
            statement.setTimestamp(15, Timestamp.from(entry.updatedAt()));
            statement.executeUpdate();
            return entry;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save agent catalog entry " + entry.code(), e);
        }
    }

    @Override
    public Optional<AgentCatalogEntry> findById(String agentId) {
        return findSingle("SELECT * FROM agents WHERE agent_id = ?", agentId);
    }

    @Override
    public Optional<AgentCatalogEntry> findByCode(String code) {
        return findSingle("SELECT * FROM agents WHERE lower(code) = lower(?)", code);
    }

    @Override
    public Optional<AgentCatalogEntry> findByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        for (AgentCatalogEntry entry : listAgents()) {
            if (entry.aliases().stream().anyMatch(existing -> existing.equalsIgnoreCase(alias))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<AgentCatalogEntry> listAgents() {
        List<AgentCatalogEntry> agents = new ArrayList<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM agents ORDER BY updated_at DESC")) {
            while (rs.next()) {
                agents.add(mapEntry(rs));
            }
        } catch (Exception e) {
            logger.warn("Failed to list agent catalog: {}", e.getMessage());
        }
        return agents;
    }

    @Override
    public boolean delete(String agentId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM agents WHERE agent_id = ?")) {
            statement.setString(1, agentId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logger.warn("Failed to delete agent {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    private Optional<AgentCatalogEntry> findSingle(String sql, String value) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapEntry(rs));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to query agent catalog with value {}: {}", value, e.getMessage());
        }
        return Optional.empty();
    }

    private AgentCatalogEntry mapEntry(ResultSet rs) throws Exception {
        return new AgentCatalogEntry(
                rs.getString("agent_id"),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("system_prompt"),
                fromJsonList(rs.getString("aliases_json")),
                fromJsonList(rs.getString("allowed_tools_json")),
                fromJsonList(rs.getString("allowed_skills_json")),
                fromJsonMap(rs.getString("model_config_json")),
                rs.getString("memory_scope"),
                rs.getString("status"),
                rs.getString("visibility"),
                rs.getInt("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private void initSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS agents (
                    agent_id TEXT PRIMARY KEY,
                    code TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    description TEXT,
                    system_prompt TEXT NOT NULL,
                    aliases_json TEXT NOT NULL,
                    allowed_tools_json TEXT NOT NULL,
                    allowed_skills_json TEXT NOT NULL,
                    model_config_json TEXT NOT NULL,
                    memory_scope TEXT,
                    status TEXT NOT NULL,
                    visibility TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize agent catalog schema", e);
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String toJsonList(List<String> value) throws Exception {
        return MAPPER.writeValueAsString(value != null ? value : List.of());
    }

    private String toJsonMap(Map<String, Object> value) throws Exception {
        return MAPPER.writeValueAsString(value != null ? value : Map.of());
    }

    private List<String> fromJsonList(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(value, new TypeReference<List<String>>() {});
    }

    private Map<String, Object> fromJsonMap(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : Instant.now();
    }
}

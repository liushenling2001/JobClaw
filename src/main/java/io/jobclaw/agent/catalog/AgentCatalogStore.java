package io.jobclaw.agent.catalog;

import java.util.List;
import java.util.Optional;

public interface AgentCatalogStore {

    AgentCatalogEntry save(AgentCatalogEntry entry);

    Optional<AgentCatalogEntry> findById(String agentId);

    Optional<AgentCatalogEntry> findByCode(String code);

    Optional<AgentCatalogEntry> findByAlias(String alias);

    List<AgentCatalogEntry> listAgents();

    boolean delete(String agentId);
}

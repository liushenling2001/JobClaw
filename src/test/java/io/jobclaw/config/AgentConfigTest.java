package io.jobclaw.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentConfigTest {

    @Test
    void shouldDefaultUnifiedContextPolicy() {
        AgentConfig config = new AgentConfig();

        assertEquals(80, config.getCompactionTriggerPercentage());
        assertEquals(16, config.getCompactionRetainPercentage());
        assertEquals(3600, config.getLlmCallTimeoutSeconds());
    }

    @Test
    void shouldIncludeUnifiedContextPolicyInDefaultConfig() {
        Config config = Config.defaultConfig();

        assertEquals(80, config.getAgent().getCompactionTriggerPercentage());
        assertEquals(16, config.getAgent().getCompactionRetainPercentage());
        assertEquals(3600, config.getAgent().getLlmCallTimeoutSeconds());
    }
}

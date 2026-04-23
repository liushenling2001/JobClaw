package io.jobclaw.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentConfigTest {

    @Test
    void shouldDefaultMaxRepairAttemptsToOne() {
        AgentConfig config = new AgentConfig();

        assertEquals(1, config.getMaxRepairAttempts());
        assertEquals(1, config.getMaxSubtaskRepairAttempts());
        assertEquals(300, config.getLlmCallTimeoutSeconds());
    }

    @Test
    void shouldIncludeMaxRepairAttemptsInDefaultConfig() {
        Config config = Config.defaultConfig();

        assertEquals(1, config.getAgent().getMaxRepairAttempts());
        assertEquals(1, config.getAgent().getMaxSubtaskRepairAttempts());
        assertEquals(300, config.getAgent().getLlmCallTimeoutSeconds());
    }
}

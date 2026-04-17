package io.jobclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionRoleInheritanceTest {

    @Test
    void builtInRoleShouldInheritMainAssistantToolsByDefault() {
        AgentDefinition reviewer = AgentDefinition.fromRole(AgentRole.REVIEWER);

        assertNull(reviewer.getAllowedTools());
        assertFalse(reviewer.hasToolRestrictions());
        assertTrue(reviewer.isToolAllowed("skills"));
        assertTrue(reviewer.isToolAllowed("subtasks"));
        assertTrue(reviewer.isToolAllowed("spawn"));
    }

    @Test
    void explicitAgentDefinitionShouldStillHonorToolRestrictions() {
        AgentDefinition restricted = AgentDefinition.of("restricted", "prompt", List.of("read_file"));

        assertTrue(restricted.hasToolRestrictions());
        assertTrue(restricted.isToolAllowed("read_file"));
        assertFalse(restricted.isToolAllowed("skills"));
        assertFalse(restricted.isToolAllowed("subtasks"));
    }
}

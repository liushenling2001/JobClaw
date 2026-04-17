package io.jobclaw.agent.profile;

import io.jobclaw.agent.AgentDefinition;

public record ResolvedAgentRuntime(
        AgentProfile profile,
        AgentDefinition definition,
        boolean inheritedFromParent
) {
}

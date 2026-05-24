package io.jobclaw.agent.runtime;

import java.util.UUID;

public final class AgentRunIds {

    private AgentRunIds() {
    }

    public static String newTopLevelRunId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String newChildRunId() {
        return "child-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

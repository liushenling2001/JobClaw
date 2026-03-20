package io.jobclaw.agent;

import org.springframework.stereotype.Component;

/**
 * Agent 常量定义
 */
@Component
public class AgentConstants {

    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final int DEFAULT_MAX_TOOL_ITERATIONS = 20;
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final String DEFAULT_MODEL = "qwen3.5-plus";

    public static final String SYSTEM_SESSION_KEY = "system:session";
    public static final String HEARTBEAT_SESSION_KEY = "system:heartbeat";
    public static final String CRON_SESSION_KEY = "system:cron";

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";
}

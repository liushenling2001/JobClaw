package io.jobclaw.agent;

import org.springframework.stereotype.Component;

/**
 * Agent 常量定义
 */
@Component
public class AgentConstants {

    // ==================== LLM 基础配置 ====================
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final int DEFAULT_MAX_TOOL_ITERATIONS = 20;
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final String DEFAULT_MODEL = "qwen3.5-plus";

    // ==================== 系统会话 Key ====================
    public static final String SYSTEM_SESSION_KEY = "system:session";
    public static final String HEARTBEAT_SESSION_KEY = "system:heartbeat";
    public static final String CRON_SESSION_KEY = "system:cron";

    // ==================== 消息角色 ====================
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    // ==================== 会话摘要配置 ====================
    /** 触发摘要的消息数量阈值 */
    public static final int SUMMARIZE_MESSAGE_THRESHOLD = 20;
    /** 触发摘要的 Token 比例 (上下文窗口的 40%) */
    public static final int SUMMARIZE_TOKEN_PERCENTAGE = 40;
    /** 摘要后保留的最近消息数 */
    public static final int RECENT_MESSAGES_TO_KEEP = 5;
    /** 分批摘要的消息阈值，超过此值采用分批策略 */
    public static final int BATCH_SUMMARIZE_THRESHOLD = 30;
    /** 摘要生成的最大 Token 数 */
    public static final int SUMMARY_MAX_TOKENS = 512;
    /** 摘要生成的温度参数 */
    public static final double SUMMARY_TEMPERATURE = 0.3;

    // ==================== 记忆系统配置 ====================
    /** 记忆 token 预算占上下文窗口的百分比 */
    public static final int MEMORY_TOKEN_BUDGET_PERCENTAGE = 15;
    /** 记忆最小 token 预算 */
    public static final int MEMORY_MIN_TOKEN_BUDGET = 512;
    /** 记忆最大 token 预算 */
    public static final int MEMORY_MAX_TOKEN_BUDGET = 2048;
    /** 每日笔记在记忆上下文中的最大 token 占比 */
    public static final double DAILY_NOTES_TOKEN_RATIO = 0.3;
    /** 结构化记忆在记忆上下文中的最大 token 占比 */
    public static final double STRUCTURED_MEMORY_TOKEN_RATIO = 0.5;
    /** 传统记忆在记忆上下文中的最大 token 占比 */
    public static final double LEGACY_MEMORY_TOKEN_RATIO = 0.2;
    /** 单条记忆的最大 token 数 */
    public static final int MAX_SINGLE_ENTRY_TOKENS = 256;
    /** 相关性匹配的权重倍数 */
    public static final double RELEVANCE_BOOST_MULTIPLIER = 3.0;
    /** 单条消息最大 Token 数为上下文窗口的 1/2 */
    public static final int MAX_MESSAGE_TOKEN_DIVISOR = 2;
}

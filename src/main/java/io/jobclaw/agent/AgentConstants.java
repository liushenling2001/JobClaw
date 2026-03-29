package io.jobclaw.agent;

/**
 * Agent 常量定义
 *
 * 仅保留真正不变的常量（如角色名称、会话 Key 等）
 * 所有可配置参数应从 AgentConfig 中读取
 */
public class AgentConstants {

    // ==================== 系统会话 Key ====================
    public static final String SYSTEM_SESSION_KEY = "system:session";
    public static final String HEARTBEAT_SESSION_KEY = "system:heartbeat";
    public static final String CRON_SESSION_KEY = "system:cron";

    // ==================== 消息角色 ====================
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    // ==================== Token 估算配置 ====================
    /** 单条消息最大 Token 数为上下文窗口的 1/2 */
    public static final int MAX_MESSAGE_TOKEN_DIVISOR = 2;

    // ==================== 摘要生成参数（不变量） ====================
    /** 摘要生成的温度参数 */
    public static final double SUMMARY_TEMPERATURE = 0.3;

    // ==================== 记忆系统配置（默认值，实际应从 AgentConfig 读取） ====================
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
}

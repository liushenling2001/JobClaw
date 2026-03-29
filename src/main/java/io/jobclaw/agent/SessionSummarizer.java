package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryEvolver;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.AgentConfig;
import io.jobclaw.providers.Message;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话摘要器，负责管理会话摘要和历史记录压缩。
 *
 * 核心功能：
 * - 监控会话历史长度，自动触发摘要
 * - 生成会话摘要以压缩上下文
 * - 支持批量摘要和分批处理
 * - 保留最近消息，删除已摘要的历史
 *
 * 触发条件：
 * - 消息数量超过阈值（从 AgentConfig 读取）
 * - Token 数量超过上下文窗口的一定比例（从 AgentConfig 读取）
 *
 * 摘要策略：
 * 1. 保留最近的 N 条消息（从 AgentConfig 读取）
 * 2. 对较早的消息生成摘要
 * 3. 如果消息量大，采用分批摘要策略
 * 4. 合并多个批次的摘要为最终摘要
 * 5. 删除已摘要的历史，只保留摘要和最近消息
 *
 * 并发处理：
 * - 摘要操作在后台线程异步执行
 * - 使用 ConcurrentHashMap.newKeySet() 防止同一会话重复摘要
 * - 守护线程模式，不阻塞主程序退出
 */
public class SessionSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(SessionSummarizer.class);

    private static final String SUMMARY_INSTRUCTION =
            "Provide a concise summary of this conversation segment, preserving core context and key points.\n";
    private static final String EXISTING_CONTEXT_PREFIX = "Existing context: ";
    private static final String CONVERSATION_HEADER = "\nCONVERSATION:\n";
    private static final String ROLE_SEPARATOR = ": ";
    private static final String MESSAGE_SEPARATOR = "\n";

    private static final String MERGE_SUMMARY_TEMPLATE =
            "Merge these two conversation summaries into one cohesive summary:\n\n1: %s\n\n2: %s";

    private static final String OPTION_MAX_TOKENS = "max_tokens";
    private static final String OPTION_TEMPERATURE = "temperature";

    private final SessionManager sessions;
    private final AgentLoop agentLoop;
    private final AgentConfig agentConfig;
    private final int contextWindow;
    private final Set<String> summarizing;
    private final MemoryStore memoryStore;
    private final MemoryEvolver memoryEvolver;

    /**
     * 构造会话摘要器。
     *
     * @param sessions      会话管理器
     * @param agentLoop     Agent 循环（用于调用 LLM）
     * @param agentConfig   Agent 配置（包含摘要相关参数）
     * @param memoryStore   记忆存储（用于摘要完成后写入每日笔记）
     * @param memoryEvolver 记忆进化引擎（用于从摘要中提炼结构化记忆）
     */
    public SessionSummarizer(SessionManager sessions, AgentLoop agentLoop,
                             AgentConfig agentConfig, MemoryStore memoryStore,
                             MemoryEvolver memoryEvolver) {
        this.sessions = sessions;
        this.agentLoop = agentLoop;
        this.agentConfig = agentConfig;
        this.contextWindow = agentConfig.getContextWindow();
        this.memoryStore = memoryStore;
        this.memoryEvolver = memoryEvolver;
        this.summarizing = ConcurrentHashMap.newKeySet();
    }

    /**
     * 根据需要触发会话摘要。
     *
     * 检查会话历史长度和 Token 数，如果超过阈值则启动异步摘要。
     *
     * @param sessionKey 会话键
     */
    public void maybeSummarize(String sessionKey) {
        List<Message> history = sessions.getHistory(sessionKey);

        if (!shouldSummarize(history)) {
            return;
        }

        startAsyncSummarize(sessionKey);
    }

    /**
     * 判断是否应该执行摘要。
     *
     * @param history 会话历史
     * @return 需要摘要返回 true，否则返回 false
     */
    private boolean shouldSummarize(List<Message> history) {
        int tokenEstimate = estimateTokens(history);
        int threshold = contextWindow * agentConfig.getSummarizeTokenPercentage() / 100;

        return history.size() > agentConfig.getSummarizeMessageThreshold() || tokenEstimate > threshold;
    }

    /**
     * 启动异步摘要任务。
     *
     * @param sessionKey 会话键
     */
    private void startAsyncSummarize(String sessionKey) {
        if (!summarizing.add(sessionKey)) {
            return;  // 已经在摘要中，跳过
        }

        Thread thread = new Thread(() -> {
            try {
                summarize(sessionKey);
            } catch (Exception e) {
                logger.error("Async summarize failed for session: {}, error: {}",
                        sessionKey, e.getMessage());
            } finally {
                summarizing.remove(sessionKey);
            }
        });
        thread.setDaemon(true);
        thread.setName("summarizer-" + sessionKey);
        thread.start();
    }

    /**
     * 摘要一个会话。
     *
     * @param sessionKey 会话键
     */
    private void summarize(String sessionKey) {
        List<Message> history = sessions.getHistory(sessionKey);

        if (history.size() <= agentConfig.getRecentMessagesToKeep()) {
            return;
        }

        List<Message> toSummarize = extractMessagesToSummarize(history);
        List<Message> validMessages = filterValidMessages(toSummarize);

        if (validMessages.isEmpty()) {
            return;
        }

        String existingSummary = sessions.getSummary(sessionKey);
        String finalSummary = generateSummary(validMessages, existingSummary);

        if (finalSummary != null && !finalSummary.isEmpty()) {
            saveSummary(sessionKey, finalSummary, toSummarize.size(), validMessages.size());
        }
    }

    /**
     * 提取需要摘要的消息。
     *
     * @param history 完整会话历史
     * @return 需要摘要的消息列表
     */
    private List<Message> extractMessagesToSummarize(List<Message> history) {
        return new ArrayList<>(
                history.subList(0, history.size() - agentConfig.getRecentMessagesToKeep())
        );
    }

    /**
     * 过滤有效的消息。
     *
     * 只保留 user 和 assistant 角色的消息，且 Token 数不超过上下文窗口的一半。
     *
     * @param messages 原始消息列表
     * @return 过滤后的有效消息列表
     */
    private List<Message> filterValidMessages(List<Message> messages) {
        List<Message> validMessages = new ArrayList<>();
        int maxMessageTokens = contextWindow / AgentConstants.MAX_MESSAGE_TOKEN_DIVISOR;

        for (Message message : messages) {
            if (isValidRole(message) && isWithinTokenLimit(message, maxMessageTokens)) {
                validMessages.add(message);
            }
        }

        return validMessages;
    }

    /**
     * 检查消息角色是否有效。
     *
     * @param message 消息对象
     * @return 角色为 user 或 assistant 返回 true，否则返回 false
     */
    private boolean isValidRole(Message message) {
        String role = message.getRole();
        return AgentConstants.ROLE_USER.equals(role) || AgentConstants.ROLE_ASSISTANT.equals(role);
    }

    /**
     * 检查消息是否在 Token 限制内。
     *
     * @param message 消息对象
     * @param maxTokens 最大 Token 数
     * @return 在限制内返回 true，否则返回 false
     */
    private boolean isWithinTokenLimit(Message message, int maxTokens) {
        int msgTokens = estimateToken(message.getContent());
        return msgTokens <= maxTokens;
    }

    /**
     * 生成摘要。
     *
     * 根据消息数量选择策略：
     * - 消息较少时：直接摘要
     * - 消息较多时：分批摘要后合并
     *
     * @param validMessages   有效消息列表
     * @param existingSummary 现有摘要
     * @return 生成的摘要
     */
    private String generateSummary(List<Message> validMessages, String existingSummary) {
        if (validMessages.size() > agentConfig.getSummarizeMessageThreshold() / 10) {
            return generateBatchSummary(validMessages, existingSummary);
        } else {
            return summarizeBatch(validMessages, existingSummary);
        }
    }

    /**
     * 生成批量摘要。
     *
     * 将消息分成两部分，分别摘要后合并。
     *
     * @param validMessages   有效消息列表
     * @param existingSummary 现有摘要
     * @return 合并后的摘要
     */
    private String generateBatchSummary(List<Message> validMessages, String existingSummary) {
        int mid = validMessages.size() / 2;
        List<Message> part1 = validMessages.subList(0, mid);
        List<Message> part2 = validMessages.subList(mid, validMessages.size());

        String summary1 = summarizeBatch(part1, existingSummary);
        String summary2 = summarizeBatch(part2, null);

        return mergeSummaries(summary1, summary2);
    }

    /**
     * 合并两个摘要。
     *
     * @param summary1 第一个摘要
     * @param summary2 第二个摘要
     * @return 合并后的摘要
     */
    private String mergeSummaries(String summary1, String summary2) {
        if (summary1 == null) {
            return summary2;
        }
        if (summary2 == null) {
            return summary1;
        }

        String mergePrompt = String.format(MERGE_SUMMARY_TEMPLATE, summary1, summary2);

        try {
            String response = callLLMForSummary(mergePrompt);
            return response;
        } catch (Exception e) {
            logger.warn("Failed to merge summaries, concatenating instead: {}", e.getMessage());
            return summary1 + " " + summary2;
        }
    }

    /**
     * 摘要一批消息。
     *
     * @param batch           消息批次
     * @param existingSummary 现有摘要
     * @return 批次摘要
     */
    private String summarizeBatch(List<Message> batch, String existingSummary) {
        String prompt = buildSummaryPrompt(batch, existingSummary);

        try {
            return callLLMForSummary(prompt);
        } catch (Exception e) {
            logger.error("Failed to summarize batch: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 LLM 生成摘要。
     *
     * @param prompt 摘要提示词
     * @return 生成的摘要
     */
    private String callLLMForSummary(String prompt) {
        // 使用一个临时的会话键进行摘要生成
        String summarySessionKey = "system:summary:" + UUID.randomUUID();
        try {
            // 直接调用 LLM 生成摘要，不使用工具
            return agentLoop.callLLM(prompt, createSummaryOptions());
        } catch (Exception e) {
            logger.error("LLM call failed for summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建摘要提示词。
     *
     * @param batch           消息批次
     * @param existingSummary 现有摘要
     * @return 提示词
     */
    private String buildSummaryPrompt(List<Message> batch, String existingSummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(SUMMARY_INSTRUCTION);

        if (existingSummary != null && !existingSummary.isEmpty()) {
            prompt.append(EXISTING_CONTEXT_PREFIX)
                    .append(existingSummary)
                    .append(MESSAGE_SEPARATOR);
        }

        prompt.append(CONVERSATION_HEADER);
        for (Message message : batch) {
            prompt.append(message.getRole())
                    .append(ROLE_SEPARATOR)
                    .append(message.getContent())
                    .append(MESSAGE_SEPARATOR);
        }

        return prompt.toString();
    }

    /**
     * 创建摘要生成的选项。
     *
     * @return 选项 Map
     */
    private Map<String, Object> createSummaryOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put(OPTION_MAX_TOKENS, agentConfig.getMaxTokens());
        options.put(OPTION_TEMPERATURE, AgentConstants.SUMMARY_TEMPERATURE);
        return options;
    }

    /**
     * 保存摘要并清理历史。
     *
     * @param sessionKey  会话键
     * @param summary     摘要内容
     * @param originalSize 原始消息数
     * @param validSize   有效消息数
     */
    private void saveSummary(String sessionKey, String summary,
                             int originalSize, int validSize) {
        try {
            sessions.setSummary(sessionKey, summary);
            sessions.truncateHistory(sessionKey, agentConfig.getRecentMessagesToKeep());
            Session session = sessions.getOrCreate(sessionKey);
            sessions.save(session);
        } catch (Exception e) {
            logger.error("Failed to persist summary for session: {}, error: {}",
                    sessionKey, e.getMessage());
            return;
        }

        // 将摘要写入每日笔记，形成可追溯的对话记录
        try {
            String dailyNote = String.format("[%s] %s", sessionKey, summary);
            memoryStore.appendToday(dailyNote);
        } catch (Exception e) {
            logger.warn("Failed to write daily note for summary: {}", e.getMessage());
        }

        // 从摘要中快速提炼结构化记忆
        if (memoryEvolver != null) {
            try {
                memoryEvolver.quickExtractFromSummary(sessionKey, summary);
            } catch (Exception e) {
                logger.warn("Failed to extract memory from summary: {}", e.getMessage());
            }
        }

        logger.info("Session summarized: session={}, original_messages={}, valid_messages={}",
                sessionKey, originalSize, validSize);
    }

    /**
     * 估算消息列表的总 Token 数。
     *
     * @param messages 消息列表
     * @return 估算的 Token 总数
     */
    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateToken(message.getContent());
        }
        return total;
    }

    /**
     * 估算单个消息的 Token 数。
     *
     * @param content 消息内容
     * @return 估算的 Token 数
     */
    private int estimateToken(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int englishChars = 0;
        for (char c : content.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                englishChars++;
            }
        }
        return chineseChars + englishChars / 4;
    }
}

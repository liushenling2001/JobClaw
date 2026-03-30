package io.jobclaw.agent;

import io.jobclaw.agent.evolution.MemoryEvolver;
import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.config.AgentConfig;
import io.jobclaw.conversation.MessageChunk;
import io.jobclaw.conversation.StoredMessage;
import io.jobclaw.providers.Message;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.ChunkSummary;
import io.jobclaw.summary.MemoryFact;
import io.jobclaw.summary.SessionSummaryRecord;
import io.jobclaw.summary.SummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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
    private static final String STRUCTURED_CHUNK_TEMPLATE = """
            Given the conversation summary below, extract structured fields.
            Return plain text with exactly these sections:
            ENTITIES:
            - ...
            TOPICS:
            - ...
            DECISIONS:
            - ...
            OPEN_QUESTIONS:
            - ...

            If a section is empty, write `- none`.

            SUMMARY:
            %s
            """;
    private static final String MEMORY_FACT_TEMPLATE = """
            Extract durable memory facts from the conversation messages below.
            Return one fact per line using this exact pipe-delimited format:
            FACT|factType|subject|predicate|objectText|confidence

            Allowed factType values:
            constraint
            preference
            important_file
            active_goal
            project_context

            Only include facts that are likely to remain useful in later turns.
            Confidence must be a decimal between 0.0 and 1.0.
            If there are no useful facts, return exactly: NONE

            CONVERSATION:
            %s
            """;

    private static final String OPTION_MAX_TOKENS = "max_tokens";
    private static final String OPTION_TEMPERATURE = "temperature";

    private final SessionManager sessions;
    private final AgentLoop agentLoop;
    private final AgentConfig agentConfig;
    private final int contextWindow;
    private final Set<String> summarizing;
    private final MemoryStore memoryStore;
    private final MemoryEvolver memoryEvolver;
    private final SummaryService summaryService;

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
                             MemoryEvolver memoryEvolver,
                             SummaryService summaryService) {
        this.sessions = sessions;
        this.agentLoop = agentLoop;
        this.agentConfig = agentConfig;
        this.contextWindow = agentConfig.getContextWindow();
        this.memoryStore = memoryStore;
        this.memoryEvolver = memoryEvolver;
        this.summaryService = summaryService;
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
        long summarizedSequence = summaryService.getSessionSummary(sessionKey)
                .map(SessionSummaryRecord::sourceChunkEndSequence)
                .orElse(0L);
        int keepRecent = agentConfig.getRecentMessagesToKeep();
        int eligibleEndExclusive = history.size() - keepRecent;

        if (eligibleEndExclusive <= summarizedSequence) {
            return;
        }

        List<Message> toSummarize = extractMessagesToSummarize(history, summarizedSequence, eligibleEndExclusive);
        List<Message> validMessages = filterValidMessages(toSummarize);

        if (validMessages.isEmpty()) {
            return;
        }

        String existingSummary = summaryService.getSessionSummary(sessionKey)
                .map(SessionSummaryRecord::summaryText)
                .filter(summary -> !summary.isBlank())
                .orElse(sessions.getSummary(sessionKey));
        String chunkSummary = summarizeBatch(validMessages, null);
        if (chunkSummary == null || chunkSummary.isEmpty()) {
            return;
        }
        String finalSummary = mergeSummaries(existingSummary, chunkSummary);

        if (finalSummary != null && !finalSummary.isEmpty()) {
            saveSummary(sessionKey, toSummarize, validMessages, chunkSummary, finalSummary, eligibleEndExclusive);
        }
    }

    /**
     * 提取需要摘要的消息。
     *
     * @param history 完整会话历史
     * @return 需要摘要的消息列表
     */
    private List<Message> extractMessagesToSummarize(List<Message> history, long summarizedSequence, int eligibleEndExclusive) {
        int startIndex = (int) Math.max(0, summarizedSequence);
        int endIndex = Math.max(startIndex, eligibleEndExclusive);
        return new ArrayList<>(history.subList(startIndex, endIndex));
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
    private void saveSummary(String sessionKey,
                             List<Message> sourceMessages,
                             List<Message> validMessages,
                             String chunkSummary,
                             String summary,
                             int sourceEndSequenceExclusive) {
        try {
            sessions.setSummary(sessionKey, summary);
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

        persistStructuredSummary(sessionKey, sourceMessages, validMessages, chunkSummary, summary, sourceEndSequenceExclusive);

        // 从摘要中快速提炼结构化记忆
        if (memoryEvolver != null) {
            try {
                memoryEvolver.quickExtractFromSummary(sessionKey, summary);
            } catch (Exception e) {
                logger.warn("Failed to extract memory from summary: {}", e.getMessage());
            }
        }

        logger.info("Session summarized: session={}, original_messages={}, valid_messages={}, source_end_sequence={}",
                sessionKey, sourceMessages.size(), validMessages.size(), sourceEndSequenceExclusive);
    }

    private void persistStructuredSummary(String sessionKey,
                                          List<Message> sourceMessages,
                                          List<Message> validMessages,
                                          String chunkSummaryText,
                                          String sessionSummaryText,
                                          int sourceEndSequenceExclusive) {
        if (sourceMessages == null || sourceMessages.isEmpty()) {
            return;
        }

        long startSequence = Math.max(1L, sourceEndSequenceExclusive - sourceMessages.size() + 1L);
        long endSequence = sourceEndSequenceExclusive;
        Instant now = Instant.now();
        String chunkId = sessionKey + ":chunk:" + endSequence;
        int chunkVersion = summaryService.listChunkSummaries(sessionKey).size() + 1;
        StructuredChunkData structuredChunk = extractStructuredChunkData(chunkSummaryText);

        MessageChunk chunk = new MessageChunk(
                chunkId,
                sessionKey,
                startSequence,
                endSequence,
                sourceMessages.size(),
                estimateTokens(validMessages),
                "summarized",
                now,
                now
        );
        sessions.saveChunk(chunk);

        ChunkSummary chunkSummary = new ChunkSummary(
                chunkId,
                sessionKey,
                chunkSummaryText,
                structuredChunk.entities(),
                structuredChunk.topics(),
                structuredChunk.decisions(),
                structuredChunk.openQuestions(),
                chunkVersion,
                now
        );
        summaryService.saveChunkSummary(chunkSummary);

        SessionSummaryRecord sessionSummary = new SessionSummaryRecord(
                sessionKey,
                sessionSummaryText,
                deriveActiveGoals(structuredChunk, sessionSummaryText),
                deriveConstraints(sessionKey, sourceMessages),
                extractImportantFiles(sourceMessages),
                endSequence,
                summaryService.getSessionSummary(sessionKey).map(SessionSummaryRecord::version).orElse(0) + 1,
                now
        );
        summaryService.saveSessionSummary(sessionSummary);

        List<StoredMessage> storedMessages = toStoredMessages(sessionKey, sourceMessages, startSequence, now);
        Map<String, MemoryFact> mergedFacts = new LinkedHashMap<>();
        for (MemoryFact fact : summaryService.listMemoryFacts(sessionKey)) {
            mergedFacts.put(fact.factId(), fact);
        }
        for (MemoryFact fact : summaryService.extractFacts(sessionKey, storedMessages)) {
            mergedFacts.put(fact.factId(), fact);
        }
        for (MemoryFact fact : extractFactsWithLLM(sessionKey, storedMessages, now)) {
            mergedFacts.put(fact.factId(), fact);
        }
        summaryService.replaceMemoryFacts(sessionKey, new ArrayList<>(mergedFacts.values()));
    }

    private StructuredChunkData extractStructuredChunkData(String chunkSummaryText) {
        if (chunkSummaryText == null || chunkSummaryText.isBlank()) {
            return StructuredChunkData.empty();
        }

        String prompt = String.format(STRUCTURED_CHUNK_TEMPLATE, chunkSummaryText);
        try {
            String response = callLLMForSummary(prompt);
            if (response == null || response.isBlank()) {
                return StructuredChunkData.empty();
            }
            return parseStructuredChunkData(response);
        } catch (Exception e) {
            logger.warn("Failed to extract structured chunk data: {}", e.getMessage());
            return StructuredChunkData.empty();
        }
    }

    private StructuredChunkData parseStructuredChunkData(String response) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = null;

        for (String rawLine : response.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.endsWith(":")) {
                currentSection = line.substring(0, line.length() - 1).trim().toUpperCase(Locale.ROOT);
                sections.putIfAbsent(currentSection, new ArrayList<>());
                continue;
            }
            if (currentSection == null) {
                continue;
            }
            String value = line.startsWith("-") ? line.substring(1).trim() : line;
            if (!value.isEmpty() && !"none".equalsIgnoreCase(value)) {
                sections.get(currentSection).add(value);
            }
        }

        return new StructuredChunkData(
                normalizeSection(sections.get("ENTITIES")),
                normalizeSection(sections.get("TOPICS")),
                normalizeSection(sections.get("DECISIONS")),
                normalizeSection(sections.get("OPEN_QUESTIONS"))
        );
    }

    private List<String> normalizeSection(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .limit(8)
                .toList();
    }

    private List<String> deriveActiveGoals(StructuredChunkData structuredChunk, String sessionSummaryText) {
        List<String> goals = new ArrayList<>();
        goals.addAll(structuredChunk.decisions());
        goals.addAll(structuredChunk.openQuestions());
        if (goals.isEmpty() && sessionSummaryText != null && !sessionSummaryText.isBlank()) {
            goals.add(sessionSummaryText.length() > 160 ? sessionSummaryText.substring(0, 160) : sessionSummaryText);
        }
        return goals.stream().distinct().limit(8).toList();
    }

    private List<String> deriveConstraints(String sessionKey, List<Message> sourceMessages) {
        List<StoredMessage> storedMessages = toStoredMessages(sessionKey, sourceMessages, 1, Instant.now());
        List<String> constraints = new ArrayList<>();
        for (MemoryFact fact : summaryService.extractFacts(sessionKey, storedMessages)) {
            if ("constraint".equals(fact.factType()) && fact.objectText() != null && !fact.objectText().isBlank()) {
                constraints.add(fact.objectText());
            }
        }
        return constraints.stream().distinct().limit(10).toList();
    }

    private List<String> extractImportantFiles(List<Message> messages) {
        List<StoredMessage> storedMessages = toStoredMessages("session", messages, 1, Instant.now());
        List<String> files = new ArrayList<>();
        for (MemoryFact fact : summaryService.extractFacts("session", storedMessages)) {
            if ("important_file".equals(fact.factType()) && fact.objectText() != null) {
                files.add(fact.objectText());
            }
        }
        return files.stream().distinct().limit(20).toList();
    }

    private List<StoredMessage> toStoredMessages(String sessionKey, List<Message> messages, long startSequence, Instant createdAt) {
        List<StoredMessage> storedMessages = new ArrayList<>();
        long sequence = startSequence;
        for (Message message : messages) {
            storedMessages.add(new StoredMessage(
                    sessionKey + "-" + sequence,
                    sessionKey,
                    sequence,
                    message.getRole(),
                    message.getContent(),
                    null,
                    message.getToolCallId(),
                    null,
                    null,
                    Map.of(),
                    createdAt
            ));
            sequence++;
        }
        return storedMessages;
    }

    private List<MemoryFact> extractFactsWithLLM(String sessionKey, List<StoredMessage> storedMessages, Instant now) {
        if (storedMessages == null || storedMessages.isEmpty()) {
            return List.of();
        }

        String conversation = buildConversationForFactExtraction(storedMessages);
        String prompt = String.format(MEMORY_FACT_TEMPLATE, conversation);
        try {
            String response = callLLMForSummary(prompt);
            return parseMemoryFacts(sessionKey, storedMessages, response, now);
        } catch (Exception e) {
            logger.warn("Failed to extract memory facts with LLM: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildConversationForFactExtraction(List<StoredMessage> storedMessages) {
        StringBuilder builder = new StringBuilder();
        for (StoredMessage message : storedMessages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            builder.append(message.role())
                    .append(ROLE_SEPARATOR)
                    .append(message.content())
                    .append(MESSAGE_SEPARATOR);
        }
        return builder.toString();
    }

    private List<MemoryFact> parseMemoryFacts(String sessionKey,
                                              List<StoredMessage> storedMessages,
                                              String response,
                                              Instant now) {
        if (response == null || response.isBlank() || "NONE".equalsIgnoreCase(response.trim())) {
            return List.of();
        }

        StoredMessage lastMessage = storedMessages.get(storedMessages.size() - 1);
        List<MemoryFact> facts = new ArrayList<>();
        for (String rawLine : response.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("FACT|")) {
                continue;
            }

            String[] parts = line.split("\\|", 6);
            if (parts.length < 6) {
                continue;
            }

            String factType = parts[1].trim();
            String subject = parts[2].trim();
            String predicate = parts[3].trim();
            String objectText = parts[4].trim();
            if (factType.isEmpty() || subject.isEmpty() || predicate.isEmpty() || objectText.isEmpty()) {
                continue;
            }

            double confidence = parseConfidence(parts[5].trim());
            String factId = factType + ":" + Integer.toHexString((subject + "|" + predicate + "|" + objectText).toLowerCase(Locale.ROOT).hashCode());
            facts.add(new MemoryFact(
                    factId,
                    sessionKey,
                    "session",
                    factType,
                    subject,
                    predicate,
                    objectText,
                    Map.of(
                            "source", "llm",
                            "messageId", lastMessage.messageId(),
                            "sequence", lastMessage.sequence()
                    ),
                    confidence,
                    true,
                    now,
                    now
            ));
        }
        return facts;
    }

    private double parseConfidence(String raw) {
        try {
            double value = Double.parseDouble(raw);
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException e) {
            return 0.6;
        }
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

    private record StructuredChunkData(
            List<String> entities,
            List<String> topics,
            List<String> decisions,
            List<String> openQuestions
    ) {
        private static StructuredChunkData empty() {
            return new StructuredChunkData(List.of(), List.of(), List.of(), List.of());
        }
    }
}

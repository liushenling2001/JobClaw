package io.jobclaw.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 配置类
 */
public class AgentConfig {

    private String workspace;
    private String model;
    private String provider;
    private int maxTokens;
    private double temperature;
    private int maxToolIterations;
    private int maxRepairAttempts;
    private int maxVerificationRepairAttempts;
    private int maxFileExpectationRepairAttempts;
    private int maxTestCommandRepairAttempts;
    private int maxCommandExitRepairAttempts;
    private boolean restrictToWorkspace;
    private boolean heartbeatEnabled;
    private boolean feedbackEnabled;
    private boolean promptOptimizationEnabled;
    private boolean collaborationEnabled;
    private int maxToolOutputLength;
    private int toolCallTimeoutSeconds;
    private long subtaskTimeoutMs;
    private List<String> commandBlacklist;

    // ==================== 上下文管理配置 ====================
    /** 上下文窗口大小（token 数），默认 128K */
    private int contextWindow;
    /** 触发摘要的消息数量阈值 */
    private int summarizeMessageThreshold;
    /** 触发摘要的 Token 比例（百分比） */
    private int summarizeTokenPercentage;
    /** 摘要后保留的最近消息数 */
    private int recentMessagesToKeep;
    /** 记忆 token 预算占上下文窗口的百分比 */
    private int memoryTokenBudgetPercentage;
    /** 记忆最小 token 预算 */
    private int memoryMinTokenBudget;
    /** 记忆最大 token 预算 */
    private int memoryMaxTokenBudget;
    /** prompt 可用 token 预算占上下文窗口的百分比 */
    private int contextMaxPromptTokenPercentage;
    /** 长输入时 prompt 可用 token 预算占上下文窗口的百分比 */
    private int contextLongInputPromptTokenPercentage;
    /** 判定为长输入的 token 百分比阈值 */
    private int contextLongInputTokenPercentage;
    /** history retrieval 最大条数 */
    private int contextMaxHistoryRetrieval;
    /** summary retrieval 最大条数 */
    private int contextMaxSummaryRetrieval;
    /** memory retrieval 最大条数 */
    private int contextMaxMemoryRetrieval;

    public AgentConfig() {
        this.workspace = "~/.jobclaw/workspace";
        this.model = "qwen3.5-plus";
        this.provider = "dashscope";
        this.maxTokens = 16384;
        this.temperature = 0.7;
        this.maxToolIterations = 20;
        this.maxRepairAttempts = 1;
        this.maxVerificationRepairAttempts = 1;
        this.maxFileExpectationRepairAttempts = 2;
        this.maxTestCommandRepairAttempts = 1;
        this.maxCommandExitRepairAttempts = 1;
        this.restrictToWorkspace = true;
        this.heartbeatEnabled = false;
        this.feedbackEnabled = false;
        this.promptOptimizationEnabled = false;
        this.collaborationEnabled = true;
        this.maxToolOutputLength = 10000; // 默认限制工具返回 10000 字符
        this.toolCallTimeoutSeconds = 120;
        this.subtaskTimeoutMs = 300_000L;
        this.commandBlacklist = new ArrayList<>();
        // 上下文管理默认值（参考 TinyClaw）
        this.contextWindow = 128_000;
        this.summarizeMessageThreshold = 200;
        this.summarizeTokenPercentage = 90;
        this.recentMessagesToKeep = 40;
        this.memoryTokenBudgetPercentage = 20;
        this.memoryMinTokenBudget = 1024;
        this.memoryMaxTokenBudget = 16384;
        this.contextMaxPromptTokenPercentage = 75;
        this.contextLongInputPromptTokenPercentage = 60;
        this.contextLongInputTokenPercentage = 6;
        this.contextMaxHistoryRetrieval = 10;
        this.contextMaxSummaryRetrieval = 6;
        this.contextMaxMemoryRetrieval = 10;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = maxToolIterations;
    }

    public int getMaxRepairAttempts() {
        return maxRepairAttempts;
    }

    public void setMaxRepairAttempts(int maxRepairAttempts) {
        this.maxRepairAttempts = maxRepairAttempts;
    }

    public int getMaxVerificationRepairAttempts() {
        return maxVerificationRepairAttempts;
    }

    public void setMaxVerificationRepairAttempts(int maxVerificationRepairAttempts) {
        this.maxVerificationRepairAttempts = maxVerificationRepairAttempts;
    }

    public int getMaxFileExpectationRepairAttempts() {
        return maxFileExpectationRepairAttempts;
    }

    public void setMaxFileExpectationRepairAttempts(int maxFileExpectationRepairAttempts) {
        this.maxFileExpectationRepairAttempts = maxFileExpectationRepairAttempts;
    }

    public int getMaxTestCommandRepairAttempts() {
        return maxTestCommandRepairAttempts;
    }

    public void setMaxTestCommandRepairAttempts(int maxTestCommandRepairAttempts) {
        this.maxTestCommandRepairAttempts = maxTestCommandRepairAttempts;
    }

    public int getMaxCommandExitRepairAttempts() {
        return maxCommandExitRepairAttempts;
    }

    public void setMaxCommandExitRepairAttempts(int maxCommandExitRepairAttempts) {
        this.maxCommandExitRepairAttempts = maxCommandExitRepairAttempts;
    }

    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }

    public void setRestrictToWorkspace(boolean restrictToWorkspace) {
        this.restrictToWorkspace = restrictToWorkspace;
    }

    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    public void setHeartbeatEnabled(boolean heartbeatEnabled) {
        this.heartbeatEnabled = heartbeatEnabled;
    }

    public boolean isFeedbackEnabled() {
        return feedbackEnabled;
    }

    public void setFeedbackEnabled(boolean feedbackEnabled) {
        this.feedbackEnabled = feedbackEnabled;
    }

    public boolean isPromptOptimizationEnabled() {
        return promptOptimizationEnabled;
    }

    public void setPromptOptimizationEnabled(boolean promptOptimizationEnabled) {
        this.promptOptimizationEnabled = promptOptimizationEnabled;
    }

    public boolean isCollaborationEnabled() {
        return collaborationEnabled;
    }

    public void setCollaborationEnabled(boolean collaborationEnabled) {
        this.collaborationEnabled = collaborationEnabled;
    }

    public int getMaxToolOutputLength() {
        return maxToolOutputLength;
    }

    public void setMaxToolOutputLength(int maxToolOutputLength) {
        this.maxToolOutputLength = maxToolOutputLength;
    }

    public int getToolCallTimeoutSeconds() {
        return toolCallTimeoutSeconds;
    }

    public void setToolCallTimeoutSeconds(int toolCallTimeoutSeconds) {
        this.toolCallTimeoutSeconds = toolCallTimeoutSeconds;
    }

    public long getSubtaskTimeoutMs() {
        return subtaskTimeoutMs;
    }

    public void setSubtaskTimeoutMs(long subtaskTimeoutMs) {
        this.subtaskTimeoutMs = subtaskTimeoutMs;
    }

    public List<String> getCommandBlacklist() {
        return commandBlacklist;
    }

    public void setCommandBlacklist(List<String> commandBlacklist) {
        this.commandBlacklist = commandBlacklist;
    }

    // ==================== 上下文管理配置 getter/setter ====================
    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public int getSummarizeMessageThreshold() {
        return summarizeMessageThreshold;
    }

    public void setSummarizeMessageThreshold(int summarizeMessageThreshold) {
        this.summarizeMessageThreshold = summarizeMessageThreshold;
    }

    public int getSummarizeTokenPercentage() {
        return summarizeTokenPercentage;
    }

    public void setSummarizeTokenPercentage(int summarizeTokenPercentage) {
        this.summarizeTokenPercentage = summarizeTokenPercentage;
    }

    public int getRecentMessagesToKeep() {
        return recentMessagesToKeep;
    }

    public void setRecentMessagesToKeep(int recentMessagesToKeep) {
        this.recentMessagesToKeep = recentMessagesToKeep;
    }

    public int getMemoryTokenBudgetPercentage() {
        return memoryTokenBudgetPercentage;
    }

    public void setMemoryTokenBudgetPercentage(int memoryTokenBudgetPercentage) {
        this.memoryTokenBudgetPercentage = memoryTokenBudgetPercentage;
    }

    public int getMemoryMinTokenBudget() {
        return memoryMinTokenBudget;
    }

    public void setMemoryMinTokenBudget(int memoryMinTokenBudget) {
        this.memoryMinTokenBudget = memoryMinTokenBudget;
    }

    public int getMemoryMaxTokenBudget() {
        return memoryMaxTokenBudget;
    }

    public void setMemoryMaxTokenBudget(int memoryMaxTokenBudget) {
        this.memoryMaxTokenBudget = memoryMaxTokenBudget;
    }

    public int getContextMaxPromptTokenPercentage() {
        return contextMaxPromptTokenPercentage;
    }

    public void setContextMaxPromptTokenPercentage(int contextMaxPromptTokenPercentage) {
        this.contextMaxPromptTokenPercentage = contextMaxPromptTokenPercentage;
    }

    public int getContextLongInputPromptTokenPercentage() {
        return contextLongInputPromptTokenPercentage;
    }

    public void setContextLongInputPromptTokenPercentage(int contextLongInputPromptTokenPercentage) {
        this.contextLongInputPromptTokenPercentage = contextLongInputPromptTokenPercentage;
    }

    public int getContextLongInputTokenPercentage() {
        return contextLongInputTokenPercentage;
    }

    public void setContextLongInputTokenPercentage(int contextLongInputTokenPercentage) {
        this.contextLongInputTokenPercentage = contextLongInputTokenPercentage;
    }

    public int getContextMaxHistoryRetrieval() {
        return contextMaxHistoryRetrieval;
    }

    public void setContextMaxHistoryRetrieval(int contextMaxHistoryRetrieval) {
        this.contextMaxHistoryRetrieval = contextMaxHistoryRetrieval;
    }

    public int getContextMaxSummaryRetrieval() {
        return contextMaxSummaryRetrieval;
    }

    public void setContextMaxSummaryRetrieval(int contextMaxSummaryRetrieval) {
        this.contextMaxSummaryRetrieval = contextMaxSummaryRetrieval;
    }

    public int getContextMaxMemoryRetrieval() {
        return contextMaxMemoryRetrieval;
    }

    public void setContextMaxMemoryRetrieval(int contextMaxMemoryRetrieval) {
        this.contextMaxMemoryRetrieval = contextMaxMemoryRetrieval;
    }
}

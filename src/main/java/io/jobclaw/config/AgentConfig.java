package io.jobclaw.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 配置类
 */
public class AgentConfig {

    private String workspace;
    private String model;
    private String provider;
    private String thinkingMode;
    private String reasoningEffort;
    private int thinkingTokenBudget;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int maxTokens;
    private double temperature;
    private boolean restrictToWorkspace;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int maxToolOutputLength;
    private int toolCallTimeoutSeconds;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private boolean toolRepeatGuardEnabled;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int toolRepeatGuardThreshold;
    private int llmCallTimeoutSeconds;
    private long childAgentTimeoutMs;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int childAgentResultMaxChars;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private boolean contextRefEnabled;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int contextRefThresholdChars;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int contextRefTurnBudgetChars;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int contextRefPreviewChars;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int contextRefReadMaxChars;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int contextRefReadTurnBudgetChars;
    private List<String> commandBlacklist;

    // ==================== 上下文管理配置 ====================
    /** 上下文窗口大小（token 数），默认 128K */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int contextWindow;
    /** 工具循环轨迹达到模型上下文窗口的此百分比时触发压缩 */
    private int compactionTriggerPercentage;
    /** 压缩时原样保留的近期轨迹占模型上下文窗口的百分比 */
    private int compactionRetainPercentage;

    public AgentConfig() {
        this.workspace = "~/.jobclaw/workspace";
        this.model = "qwen3.5-plus";
        this.provider = "dashscope";
        this.thinkingMode = "auto";
        this.reasoningEffort = "auto";
        this.thinkingTokenBudget = 0;
        this.maxTokens = 16384;
        this.temperature = 0.7;
        this.restrictToWorkspace = true;
        this.maxToolOutputLength = 10000; // 工具结果在前端事件中的展示截断长度，不截断模型流程内容
        this.toolCallTimeoutSeconds = 300;
        this.toolRepeatGuardEnabled = true;
        this.toolRepeatGuardThreshold = 3;
        this.llmCallTimeoutSeconds = 3600;
        this.childAgentTimeoutMs = 900_000L;
        this.childAgentResultMaxChars = 4000;
        this.contextRefEnabled = true;
        this.contextRefThresholdChars = 20_000;
        this.contextRefTurnBudgetChars = 45_000;
        this.contextRefPreviewChars = 2_000;
        this.contextRefReadMaxChars = 12_000;
        this.contextRefReadTurnBudgetChars = 45_000;
        this.commandBlacklist = new ArrayList<>();
        // 唯一的上下文压力策略：达到 80% 时压缩，保留 16% 近期原文。
        this.contextWindow = 128_000;
        this.compactionTriggerPercentage = 80;
        this.compactionRetainPercentage = 16;
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

    public String getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(String thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public int getThinkingTokenBudget() {
        return thinkingTokenBudget;
    }

    public void setThinkingTokenBudget(int thinkingTokenBudget) {
        this.thinkingTokenBudget = Math.max(0, thinkingTokenBudget);
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

    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }

    public void setRestrictToWorkspace(boolean restrictToWorkspace) {
        this.restrictToWorkspace = restrictToWorkspace;
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

    public boolean isToolRepeatGuardEnabled() {
        return toolRepeatGuardEnabled;
    }

    public void setToolRepeatGuardEnabled(boolean toolRepeatGuardEnabled) {
        this.toolRepeatGuardEnabled = toolRepeatGuardEnabled;
    }

    public int getToolRepeatGuardThreshold() {
        return toolRepeatGuardThreshold;
    }

    public void setToolRepeatGuardThreshold(int toolRepeatGuardThreshold) {
        this.toolRepeatGuardThreshold = toolRepeatGuardThreshold;
    }

    public int getLlmCallTimeoutSeconds() {
        return llmCallTimeoutSeconds;
    }

    public void setLlmCallTimeoutSeconds(int llmCallTimeoutSeconds) {
        this.llmCallTimeoutSeconds = llmCallTimeoutSeconds;
    }

    public long getChildAgentTimeoutMs() {
        return childAgentTimeoutMs;
    }

    public void setChildAgentTimeoutMs(long childAgentTimeoutMs) {
        this.childAgentTimeoutMs = childAgentTimeoutMs;
    }

    public int getChildAgentResultMaxChars() {
        return childAgentResultMaxChars;
    }

    public void setChildAgentResultMaxChars(int childAgentResultMaxChars) {
        this.childAgentResultMaxChars = childAgentResultMaxChars;
    }

    public boolean isContextRefEnabled() {
        return contextRefEnabled;
    }

    public void setContextRefEnabled(boolean contextRefEnabled) {
        this.contextRefEnabled = contextRefEnabled;
    }

    public int getContextRefThresholdChars() {
        return contextRefThresholdChars;
    }

    public void setContextRefThresholdChars(int contextRefThresholdChars) {
        this.contextRefThresholdChars = contextRefThresholdChars;
    }

    public int getContextRefTurnBudgetChars() {
        return contextRefTurnBudgetChars;
    }

    public void setContextRefTurnBudgetChars(int contextRefTurnBudgetChars) {
        this.contextRefTurnBudgetChars = contextRefTurnBudgetChars;
    }

    public int getContextRefPreviewChars() {
        return contextRefPreviewChars;
    }

    public void setContextRefPreviewChars(int contextRefPreviewChars) {
        this.contextRefPreviewChars = contextRefPreviewChars;
    }

    public int getContextRefReadMaxChars() {
        return contextRefReadMaxChars;
    }

    public void setContextRefReadMaxChars(int contextRefReadMaxChars) {
        this.contextRefReadMaxChars = contextRefReadMaxChars;
    }

    public int getContextRefReadTurnBudgetChars() {
        return contextRefReadTurnBudgetChars;
    }

    public void setContextRefReadTurnBudgetChars(int contextRefReadTurnBudgetChars) {
        this.contextRefReadTurnBudgetChars = contextRefReadTurnBudgetChars;
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

    public int getCompactionTriggerPercentage() {
        return compactionTriggerPercentage;
    }

    public void setCompactionTriggerPercentage(int compactionTriggerPercentage) {
        this.compactionTriggerPercentage = compactionTriggerPercentage;
    }

    public int getCompactionRetainPercentage() {
        return compactionRetainPercentage;
    }

    public void setCompactionRetainPercentage(int compactionRetainPercentage) {
        this.compactionRetainPercentage = compactionRetainPercentage;
    }

}

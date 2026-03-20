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
    private boolean restrictToWorkspace;
    private boolean heartbeatEnabled;
    private boolean feedbackEnabled;
    private boolean promptOptimizationEnabled;
    private boolean collaborationEnabled;
    private List<String> commandBlacklist;

    public AgentConfig() {
        this.workspace = "~/.jobclaw/workspace";
        this.model = "qwen3.5-plus";
        this.provider = "dashscope";
        this.maxTokens = 16384;
        this.temperature = 0.7;
        this.maxToolIterations = 20;
        this.restrictToWorkspace = true;
        this.heartbeatEnabled = false;
        this.feedbackEnabled = false;
        this.promptOptimizationEnabled = false;
        this.collaborationEnabled = true;
        this.commandBlacklist = new ArrayList<>();
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

    public List<String> getCommandBlacklist() {
        return commandBlacklist;
    }

    public void setCommandBlacklist(List<String> commandBlacklist) {
        this.commandBlacklist = commandBlacklist;
    }
}

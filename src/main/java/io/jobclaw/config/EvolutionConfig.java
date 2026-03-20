package io.jobclaw.config;

/**
 * 进化能力配置
 */
public class EvolutionConfig {

    private boolean feedbackEnabled;
    private boolean promptOptimizationEnabled;
    private int memoryCapacity;
    private double optimizationThreshold;

    public EvolutionConfig() {
        this.feedbackEnabled = false;
        this.promptOptimizationEnabled = false;
        this.memoryCapacity = 100;
        this.optimizationThreshold = 0.8;
    }

    public boolean isFeedbackEnabled() { return feedbackEnabled; }
    public void setFeedbackEnabled(boolean feedbackEnabled) { this.feedbackEnabled = feedbackEnabled; }
    public boolean isPromptOptimizationEnabled() { return promptOptimizationEnabled; }
    public void setPromptOptimizationEnabled(boolean promptOptimizationEnabled) { this.promptOptimizationEnabled = promptOptimizationEnabled; }
    public int getMemoryCapacity() { return memoryCapacity; }
    public void setMemoryCapacity(int memoryCapacity) { this.memoryCapacity = memoryCapacity; }
    public double getOptimizationThreshold() { return optimizationThreshold; }
    public void setOptimizationThreshold(double optimizationThreshold) { this.optimizationThreshold = optimizationThreshold; }
}

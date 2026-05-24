package io.jobclaw.config;

public class ExperienceConfig {

    private boolean llmReviewEnabled = true;
    private int llmReviewMaxInputChars = 12000;
    private int llmReviewMaxTokens = 800;
    private int llmReviewMinPendingCandidates = 1;
    private boolean memoryInjectionEnabled = true;
    private int maxInjectedMemories = 2;
    private int maxInjectedChars = 1200;
    private boolean sanitizeStatefulContent = true;
    private boolean conservativeMemoryMatching = true;

    public boolean isLlmReviewEnabled() {
        return llmReviewEnabled;
    }

    public void setLlmReviewEnabled(boolean llmReviewEnabled) {
        this.llmReviewEnabled = llmReviewEnabled;
    }

    public int getLlmReviewMaxInputChars() {
        return llmReviewMaxInputChars;
    }

    public void setLlmReviewMaxInputChars(int llmReviewMaxInputChars) {
        this.llmReviewMaxInputChars = llmReviewMaxInputChars;
    }

    public int getLlmReviewMaxTokens() {
        return llmReviewMaxTokens;
    }

    public void setLlmReviewMaxTokens(int llmReviewMaxTokens) {
        this.llmReviewMaxTokens = llmReviewMaxTokens;
    }

    public int getLlmReviewMinPendingCandidates() {
        return llmReviewMinPendingCandidates;
    }

    public void setLlmReviewMinPendingCandidates(int llmReviewMinPendingCandidates) {
        this.llmReviewMinPendingCandidates = llmReviewMinPendingCandidates;
    }

    public boolean isMemoryInjectionEnabled() {
        return memoryInjectionEnabled;
    }

    public void setMemoryInjectionEnabled(boolean memoryInjectionEnabled) {
        this.memoryInjectionEnabled = memoryInjectionEnabled;
    }

    public int getMaxInjectedMemories() {
        return maxInjectedMemories;
    }

    public void setMaxInjectedMemories(int maxInjectedMemories) {
        this.maxInjectedMemories = maxInjectedMemories;
    }

    public int getMaxInjectedChars() {
        return maxInjectedChars;
    }

    public void setMaxInjectedChars(int maxInjectedChars) {
        this.maxInjectedChars = maxInjectedChars;
    }

    public boolean isSanitizeStatefulContent() {
        return sanitizeStatefulContent;
    }

    public void setSanitizeStatefulContent(boolean sanitizeStatefulContent) {
        this.sanitizeStatefulContent = sanitizeStatefulContent;
    }

    public boolean isConservativeMemoryMatching() {
        return conservativeMemoryMatching;
    }

    public void setConservativeMemoryMatching(boolean conservativeMemoryMatching) {
        this.conservativeMemoryMatching = conservativeMemoryMatching;
    }
}

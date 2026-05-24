package io.jobclaw.config;

public class ExperienceConfig {

    private boolean llmReviewEnabled = true;
    private int llmReviewMaxInputChars = 12000;
    private int llmReviewMaxTokens = 800;
    private int llmReviewMinPendingCandidates = 1;

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
}

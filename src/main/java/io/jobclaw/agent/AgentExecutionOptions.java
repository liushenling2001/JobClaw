package io.jobclaw.agent;

/**
 * Optional per-run execution metadata supplied by CLI/daemon wrappers.
 *
 * This does not replace AgentLoop. It only lets wrappers preallocate the run id
 * and provide project-local path context for existing tools.
 */
public class AgentExecutionOptions {

    private final String runId;
    private final String parentRunId;
    private final String projectRoot;
    private final String cwd;
    private final String source;
    private final String approvalMode;
    private final String sandboxMode;
    private final String modelOverride;
    private final String agentId;

    private AgentExecutionOptions(Builder builder) {
        this.runId = blankToNull(builder.runId);
        this.parentRunId = blankToNull(builder.parentRunId);
        this.projectRoot = blankToNull(builder.projectRoot);
        this.cwd = blankToNull(builder.cwd);
        this.source = blankToNull(builder.source);
        this.approvalMode = blankToNull(builder.approvalMode);
        this.sandboxMode = blankToNull(builder.sandboxMode);
        this.modelOverride = blankToNull(builder.modelOverride);
        this.agentId = blankToNull(builder.agentId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String runId() {
        return runId;
    }

    public String parentRunId() {
        return parentRunId;
    }

    public String projectRoot() {
        return projectRoot;
    }

    public String cwd() {
        return cwd;
    }

    public String source() {
        return source;
    }

    public String approvalMode() {
        return approvalMode;
    }

    public String sandboxMode() {
        return sandboxMode;
    }

    public String modelOverride() {
        return modelOverride;
    }

    public String agentId() {
        return agentId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static class Builder {
        private String runId;
        private String parentRunId;
        private String projectRoot;
        private String cwd;
        private String source;
        private String approvalMode;
        private String sandboxMode;
        private String modelOverride;
        private String agentId;

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder parentRunId(String parentRunId) {
            this.parentRunId = parentRunId;
            return this;
        }

        public Builder projectRoot(String projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder approvalMode(String approvalMode) {
            this.approvalMode = approvalMode;
            return this;
        }

        public Builder sandboxMode(String sandboxMode) {
            this.sandboxMode = sandboxMode;
            return this;
        }

        public Builder modelOverride(String modelOverride) {
            this.modelOverride = modelOverride;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public AgentExecutionOptions build() {
            return new AgentExecutionOptions(this);
        }
    }
}

package io.jobclaw.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionContextWorkspaceTest {

    @AfterEach
    void clearContext() {
        AgentExecutionContext.clear();
    }

    @Test
    void exposesWorkingDirectoryFromCurrentScope() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:test", null, "run-test", null, null, null, null, null, "D:\\project"));

        assertThat(AgentExecutionContext.getCurrentWorkingDirectory()).isEqualTo("D:\\project");
    }

    @Test
    void clearRemovesWorkingDirectory() {
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:test", null, null, null, null, null, null, null, "D:\\project"));
        AgentExecutionContext.clear();

        assertThat(AgentExecutionContext.getCurrentWorkingDirectory()).isNull();
    }
}

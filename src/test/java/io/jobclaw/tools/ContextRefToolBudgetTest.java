package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.config.Config;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.FileResultStore;
import io.jobclaw.context.result.ResultStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRefToolBudgetTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearContext() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldLimitLargeContextRefReadBackflowPerRun() {
        Config config = Config.defaultConfig();
        config.getAgent().setContextRefReadMaxChars(10);
        config.getAgent().setContextRefReadTurnBudgetChars(15);
        ResultStore resultStore = new FileResultStore(tempDir.resolve("results"), 20);
        ContextRef ref = resultStore.save("session-1", "run-1", "tool", "read_pdf", "abcdefghijklmnopqrstuvwxyz");
        ContextRefTool tool = new ContextRefTool(resultStore, config);
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-1", null, "run-1", null, null, null, null));

        String firstRead = tool.contextRef("read", ref.getRefId(), null, "0", "10", null);
        String blockedRead = tool.contextRef("read", ref.getRefId(), null, "10", "12000", null);
        String focusedRead = tool.contextRef("read", ref.getRefId(), null, "10", "2", null);

        assertTrue(firstRead.contains("abcdefghij"));
        assertTrue(blockedRead.contains("Context reference read budget exceeded"));
        assertTrue(blockedRead.contains("context_ref(action='search'"));
        assertTrue(focusedRead.contains("kl"));
    }
}

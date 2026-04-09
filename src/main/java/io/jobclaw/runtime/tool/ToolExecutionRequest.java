package io.jobclaw.runtime.tool;

import io.jobclaw.agent.ExecutionEvent;
import org.springframework.ai.tool.ToolCallback;

import java.util.function.Consumer;

public record ToolExecutionRequest(
        String sessionKey,
        String toolName,
        String request,
        ToolCallback callback,
        Consumer<ExecutionEvent> eventCallback
) {
}

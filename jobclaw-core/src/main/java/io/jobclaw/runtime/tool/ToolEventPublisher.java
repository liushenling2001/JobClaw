package io.jobclaw.runtime.tool;

import io.jobclaw.agent.ExecutionEvent;

import java.util.Map;
import java.util.function.Consumer;

public class ToolEventPublisher {

    public void publishStart(Consumer<ExecutionEvent> callback,
                             String sessionKey,
                             String toolName,
                             String toolId,
                             String truncatedRequest) {
        publish(callback, new ExecutionEvent(
                sessionKey,
                ExecutionEvent.EventType.TOOL_START,
                "正在调用工具：" + toolName,
                Map.of(
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncatedRequest
                )
        ));
    }

    public void publishEnd(Consumer<ExecutionEvent> callback,
                           String sessionKey,
                           String toolName,
                           String toolId,
                           String truncatedRequest,
                           long durationMs) {
        publish(callback, new ExecutionEvent(
                sessionKey,
                ExecutionEvent.EventType.TOOL_END,
                "工具调用完成：" + toolName,
                Map.of(
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncatedRequest,
                        "durationMs", durationMs
                )
        ));
    }

    public void publishOutput(Consumer<ExecutionEvent> callback,
                              String sessionKey,
                              String toolName,
                              String toolId,
                              String truncatedRequest,
                              long durationMs,
                              int fullOutputLength,
                              String result) {
        publish(callback, new ExecutionEvent(
                sessionKey,
                ExecutionEvent.EventType.TOOL_OUTPUT,
                result,
                Map.of(
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncatedRequest,
                        "durationMs", durationMs,
                        "fullOutputLength", fullOutputLength
                )
        ));
    }

    public void publishProgress(Consumer<ExecutionEvent> callback,
                                String sessionKey,
                                String toolName,
                                String toolId,
                                String truncatedRequest,
                                long durationMs) {
        publish(callback, new ExecutionEvent(
                sessionKey,
                ExecutionEvent.EventType.CUSTOM,
                "工具仍在执行：" + toolName + "，已运行 " + durationMs / 1000 + " 秒",
                Map.of(
                        "source", "tool_runtime",
                        "label", "tool_progress",
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncatedRequest,
                        "durationMs", durationMs
                )
        ));
    }

    public void publishError(Consumer<ExecutionEvent> callback,
                             String sessionKey,
                             String toolName,
                             String toolId,
                             String truncatedRequest,
                             long durationMs,
                             String errorMessage) {
        publish(callback, new ExecutionEvent(
                sessionKey,
                ExecutionEvent.EventType.TOOL_ERROR,
                "工具执行失败：" + toolName + " - " + errorMessage,
                Map.of(
                        "toolName", toolName,
                        "toolId", toolId,
                        "request", truncatedRequest,
                        "durationMs", durationMs
                )
        ));
    }

    private void publish(Consumer<ExecutionEvent> callback, ExecutionEvent event) {
        if (callback != null) {
            callback.accept(event);
        }
    }
}

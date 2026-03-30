package io.jobclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Agent 执行上下文 - 使用 ThreadLocal 存储当前请求的 sessionKey 和 eventCallback
 *
 * 解决工具（如 SpawnTool、CollaborateTool）无法获取当前 sessionKey 的问题：
 * - AgentLoop 在处理请求时设置当前线程的上下文
 * - 工具执行时可以获取 sessionKey 和 eventCallback
 * - 支持嵌套调用（子 Agent 调用 spawn 等）
 *
 * 使用方式：
 * <pre>{@code
 * // 设置上下文
 * AgentExecutionContext.setCurrentContext(sessionKey, eventCallback);
 *
 * // 获取 sessionKey
 * String sessionKey = AgentExecutionContext.getCurrentSessionKey();
 *
 * // 清理
 * AgentExecutionContext.clear();
 * }</pre>
 */
public class AgentExecutionContext {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionContext.class);

    /**
     * 存储当前线程的 sessionKey
     */
    private static final ThreadLocal<String> currentSessionKey = new ThreadLocal<>();

    /**
     * 存储当前线程的 eventCallback
     */
    private static final ThreadLocal<Consumer<ExecutionEvent>> currentEventCallback = new ThreadLocal<>();

    /**
     * 设置当前线程的执行上下文
     *
     * @param sessionKey 会话键
     * @param callback 事件回调（可为 null）
     */
    public static void setCurrentContext(String sessionKey, Consumer<ExecutionEvent> callback) {
        if (sessionKey != null) {
            currentSessionKey.set(sessionKey);
            if (callback != null) {
                currentEventCallback.set(callback);
            }
            logger.debug("Set agent execution context for session: {}", sessionKey);
        }
    }

    /**
     * 获取当前线程的 sessionKey
     *
     * @return sessionKey（可能为 null）
     */
    public static String getCurrentSessionKey() {
        return currentSessionKey.get();
    }

    /**
     * 获取当前线程的 eventCallback
     *
     * @return eventCallback（可能为 null）
     */
    public static Consumer<ExecutionEvent> getCurrentEventCallback() {
        return currentEventCallback.get();
    }

    /**
     * 清理当前线程的上下文
     */
    public static void clear() {
        currentSessionKey.remove();
        currentEventCallback.remove();
        logger.debug("Cleared agent execution context");
    }

    /**
     * 检查是否有可用的上下文
     *
     * @return 是否有 sessionKey
     */
    public static boolean hasContext() {
        return currentSessionKey.get() != null;
    }

    /**
     * 发布事件（便捷方法）
     *
     * @param event 执行事件
     * @return 是否成功发布
     */
    public static boolean publishEvent(ExecutionEvent event) {
        Consumer<ExecutionEvent> callback = currentEventCallback.get();
        if (callback != null) {
            try {
                callback.accept(event);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to publish event: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }
}
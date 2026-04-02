package io.jobclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 执行跟踪服务 - 管理 Agent 执行过程的实时跟踪和 SSE 推送
 */
@Component
public class ExecutionTraceService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionTraceService.class);

    /**
     * 存储每个 session 的发射器，支持多个订阅者
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> sessionEmitters;

    /**
     * 执行事件历史（每个 session 保留最近的记录）
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<ExecutionEvent>> eventHistory;

    /**
     * 用于生成订阅者 ID
     */
    private final AtomicIntegerCounter subscriberIdCounter;

    public ExecutionTraceService() {
        this.sessionEmitters = new ConcurrentHashMap<>();
        this.eventHistory = new ConcurrentHashMap<>();
        this.subscriberIdCounter = new AtomicIntegerCounter();
        logger.info("ExecutionTraceService initialized");
    }

    /**
     * 订阅指定 session 的执行事件
     *
     * @param sessionId 会话 ID
     * @param emitter SSE 发射器
     * @return 订阅者 ID
     */
    public String subscribe(String sessionId, SseEmitter emitter) {
        String subscriberId = "sub-" + sessionId + "-" + subscriberIdCounter.incrementAndGet();

        sessionEmitters
            .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .put(subscriberId, emitter);

        // 确保历史队列存在
        eventHistory.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>());

        // 设置 emitter 回调
        emitter.onCompletion(() -> {
            logger.debug("SSE emitter completed for subscriber: {}", subscriberId);
            unsubscribe(sessionId, subscriberId);
        });

        emitter.onTimeout(() -> {
            logger.debug("SSE emitter timeout for subscriber: {}", subscriberId);
            unsubscribe(sessionId, subscriberId);
        });

        emitter.onError(e -> {
            logger.debug("SSE emitter error for subscriber {}: {}", subscriberId, e.getMessage());
            unsubscribe(sessionId, subscriberId);
        });

        logger.info("Subscriber {} subscribed to session {}", subscriberId, sessionId);
        return subscriberId;
    }

    /**
     * 取消订阅
     *
     * @param sessionId 会话 ID
     * @param subscriberId 订阅者 ID
     */
    public void unsubscribe(String sessionId, String subscriberId) {
        ConcurrentHashMap<String, SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            emitters.remove(subscriberId);
            if (emitters.isEmpty()) {
                sessionEmitters.remove(sessionId);
            }
        }
        logger.debug("Subscriber {} unsubscribed from session {}", subscriberId, sessionId);
    }

    /**
     * 发布执行事件
     *
     * @param event 执行事件
     */
    public void publish(ExecutionEvent event) {
        String sessionId = event.getSessionId();

        // 添加到历史记录
        ConcurrentLinkedQueue<ExecutionEvent> history = eventHistory.computeIfAbsent(
            sessionId, k -> new ConcurrentLinkedQueue<>());

        // 限制历史队列大小
        while (history.size() > 100) {
            history.poll();
        }
        history.offer(event);

        // 推送给所有订阅者
        ConcurrentHashMap<String, SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null && !emitters.isEmpty()) {
            Map<String, Object> sseData = event.toSseData();

            emitters.forEach((subscriberId, emitter) -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("execution-event")
                            .data(sseData));
                } catch (IOException e) {
                    logger.debug("Failed to send event to subscriber {}: {}", subscriberId, e.getMessage());
                    // 标记移除，在下次清理时实际移除
                    emitter.completeWithError(e);
                }
            });
        }

        logger.debug("Published event {} to {} subscribers", event.getType(),
            emitters != null ? emitters.size() : 0);
    }

    /**
     * 获取历史事件
     *
     * @param sessionId 会话 ID
     * @return 历史事件列表
     */
    public ConcurrentLinkedQueue<ExecutionEvent> getHistory(String sessionId) {
        return eventHistory.getOrDefault(sessionId, new ConcurrentLinkedQueue<>());
    }

    public List<ExecutionEvent> getHistoryByRun(String sessionId, String runId, int limit) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        List<ExecutionEvent> filtered = getHistory(sessionId).stream()
                .filter(event -> runId.equals(event.getRunId()))
                .toList();
        int normalizedLimit = normalizeLimit(limit);
        if (filtered.size() <= normalizedLimit) {
            return filtered;
        }
        return filtered.subList(filtered.size() - normalizedLimit, filtered.size());
    }

    public List<ExecutionEvent> getHistoryByBoard(String sessionId, String boardId, int limit) {
        if (boardId == null || boardId.isBlank()) {
            return List.of();
        }
        List<ExecutionEvent> filtered = new ArrayList<>();
        for (ExecutionEvent event : getHistory(sessionId)) {
            Object value = event.getMetadata().get("boardId");
            if (boardId.equals(value)) {
                filtered.add(event);
            }
        }
        int normalizedLimit = normalizeLimit(limit);
        if (filtered.size() <= normalizedLimit) {
            return filtered;
        }
        return filtered.subList(filtered.size() - normalizedLimit, filtered.size());
    }

    /**
     * 清除 session 的跟踪数据
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        ConcurrentHashMap<String, SseEmitter> emitters = sessionEmitters.remove(sessionId);
        if (emitters != null) {
            emitters.forEach((id, emitter) -> emitter.complete());
        }
        eventHistory.remove(sessionId);
        logger.debug("Cleared trace data for session {}", sessionId);
    }

    /**
     * 获取订阅者数量
     */
    public int getSubscriberCount(String sessionId) {
        ConcurrentHashMap<String, SseEmitter> emitters = sessionEmitters.get(sessionId);
        return emitters != null ? emitters.size() : 0;
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? Integer.MAX_VALUE : limit;
    }

    /**
     * 简单的原子整数计数器
     */
    private static class AtomicIntegerCounter {
        private int count = 0;
        public int incrementAndGet() {
            synchronized (this) {
                return ++count;
            }
        }
    }
}

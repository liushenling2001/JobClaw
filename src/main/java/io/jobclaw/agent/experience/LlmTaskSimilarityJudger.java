package io.jobclaw.agent.experience;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class LlmTaskSimilarityJudger implements TaskSimilarityJudger {

    private static final Logger logger = LoggerFactory.getLogger(LlmTaskSimilarityJudger.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int MIN_TIMEOUT_SECONDS = 5;

    private final AgentLoop agentLoop;
    private final Config config;
    private final ExecutorService executor;

    @Autowired
    public LlmTaskSimilarityJudger(@Lazy AgentLoop agentLoop, Config config) {
        this(agentLoop, config, Executors.newFixedThreadPool(2, daemonThreadFactory()));
    }

    LlmTaskSimilarityJudger(AgentLoop agentLoop, Config config, ExecutorService executor) {
        this.agentLoop = agentLoop;
        this.config = config;
        this.executor = executor;
    }

    @Override
    public boolean isSameTask(String currentTask,
                              String previousTask,
                              TaskPlanningMode planningMode,
                              DeliveryType deliveryType) {
        if (isChildRun() || currentTask == null || currentTask.isBlank()
                || previousTask == null || previousTask.isBlank()) {
            return false;
        }
        Future<Boolean> future = executor.submit(() -> callClassifier(currentTask, previousTask, planningMode, deliveryType));
        try {
            return future.get(timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("Task similarity LLM timed out after {} seconds; skipping experience guidance", timeoutSeconds());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.warn("Task similarity LLM failed; skipping experience guidance: {}", e.getMessage());
            return false;
        }
    }

    private boolean callClassifier(String currentTask,
                                   String previousTask,
                                   TaskPlanningMode planningMode,
                                   DeliveryType deliveryType) {
        String response = agentLoop.callLLM(buildPrompt(currentTask, previousTask, planningMode, deliveryType), Map.of(
                "temperature", 0.0,
                "max_tokens", 8
        ));
        if (response == null || response.isBlank()) {
            logger.debug("Task similarity LLM returned no content; skipping experience guidance");
            return false;
        }
        return response.trim().toUpperCase().startsWith("SAME");
    }

    private boolean isChildRun() {
        String parentRunId = AgentExecutionContext.getCurrentParentRunId();
        return parentRunId != null && !parentRunId.isBlank();
    }

    private int timeoutSeconds() {
        int configured = config != null && config.getAgent() != null
                ? config.getAgent().getTaskSimilarityTimeoutSeconds()
                : DEFAULT_TIMEOUT_SECONDS;
        return Math.max(MIN_TIMEOUT_SECONDS, configured > 0 ? configured : DEFAULT_TIMEOUT_SECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "jobclaw-task-similarity");
            thread.setDaemon(true);
            return thread;
        };
    }

    private String buildPrompt(String currentTask,
                               String previousTask,
                               TaskPlanningMode planningMode,
                               DeliveryType deliveryType) {
        return """
                You are a strict workflow similarity classifier.
                Decide whether the CURRENT task should reuse the SAME workflow as the PREVIOUS task.

                Rules:
                - SAME only if user intent, output type, and execution workflow are materially the same.
                - DIFFERENT if one task is review/check/audit and the other is summarize/write/report.
                - DIFFERENT if the final deliverable is different, even when both mention PDF or a folder.
                - Answer exactly SAME or DIFFERENT.

                Planning mode: %s
                Delivery type: %s

                PREVIOUS:
                %s

                CURRENT:
                %s
                """.formatted(
                planningMode != null ? planningMode.name() : "unknown",
                deliveryType != null ? deliveryType.name() : "unknown",
                previousTask,
                currentTask
        );
    }
}

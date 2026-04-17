package io.jobclaw.agent;

import io.jobclaw.agent.planning.TaskPlanningDecision;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.planning.TaskPlanningPolicy;
import io.jobclaw.agent.runtime.AgentRunIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates the primary execution paths for the main agent, role-based agents,
 * and explicit AgentDefinition executions.
 */
@Component
public class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(浣滀负|鎵紨|use|as|role)[:锛歕\\s]*(绋嬪簭鍛?|鐮旂┒鍛?|浣滃|瀹℃煡鍛?|瑙勫垝甯?|娴嬭瘯鍛榺coder|researcher|writer|reviewer|planner|tester)",
            Pattern.CASE_INSENSITIVE
    );

    private final AgentRegistry agentRegistry;
    private final TaskHarnessService taskHarnessService;
    private final TaskHarnessVerifier taskHarnessVerifier;
    private final TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder;
    private final TaskHarnessRepairStrategy taskHarnessRepairStrategy;
    private final TaskPlanningPolicy taskPlanningPolicy;

    public AgentOrchestrator(io.jobclaw.config.Config config,
                             AgentRegistry agentRegistry,
                             TaskHarnessService taskHarnessService,
                             TaskHarnessVerifier taskHarnessVerifier,
                             TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder,
                             TaskHarnessRepairStrategy taskHarnessRepairStrategy,
                             TaskPlanningPolicy taskPlanningPolicy) {
        this.agentRegistry = agentRegistry;
        this.taskHarnessService = taskHarnessService;
        this.taskHarnessVerifier = taskHarnessVerifier;
        this.taskHarnessRepairPromptBuilder = taskHarnessRepairPromptBuilder;
        this.taskHarnessRepairStrategy = taskHarnessRepairStrategy;
        this.taskPlanningPolicy = taskPlanningPolicy;
        logger.info("AgentOrchestrator initialized");
    }

    public String processWithRole(String sessionKey, String userContent, AgentRole role) {
        logger.info("Orchestrator processing with role {} for session {}", role.getDisplayName(), sessionKey);
        return handleSingleAgentWithRole(sessionKey, userContent, role, null);
    }

    public String processWithRole(String sessionKey,
                                  String userContent,
                                  AgentRole role,
                                  Consumer<ExecutionEvent> eventCallback) {
        logger.info("Orchestrator processing with role {} for session {}", role.getDisplayName(), sessionKey);
        return handleSingleAgentWithRole(sessionKey, userContent, role, eventCallback);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition) {
        return processWithDefinition(sessionKey, userContent, definition, null);
    }

    public String processWithDefinition(String sessionKey,
                                        String userContent,
                                        AgentDefinition definition,
                                        Consumer<ExecutionEvent> eventCallback) {
        return executeWithHarness(sessionKey, userContent, eventCallback, (taskInput, callback) -> {
            AgentLoop agent = agentRegistry.getOrCreateAgent(definition, sessionKey);
            if (callback != null) {
                return agent.processWithDefinition(sessionKey, taskInput, definition, callback);
            }
            return agent.processWithDefinition(sessionKey, taskInput, definition);
        });
    }

    public String process(String sessionKey, String userContent) {
        logger.info("Orchestrator processing request for session {}", sessionKey);

        if (isSubAgent(sessionKey)) {
            logger.debug("Sub-agent detected, forcing single-agent mode to prevent recursion");
            return handleSingleAgentDefault(sessionKey, userContent, null);
        }

        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            logger.info("Specified role detected: {}", specifiedRole.getDisplayName());
            return handleSingleAgentWithRole(sessionKey, userContent, specifiedRole, null);
        }

        logger.info("Single-agent mode (Agent can use spawn tool if needed)");
        return handleSingleAgentDefault(sessionKey, userContent, null);
    }

    public String process(String sessionKey, String userContent, Consumer<ExecutionEvent> eventCallback) {
        logger.info("Orchestrator processing request with callback for session {}", sessionKey);

        if (isSubAgent(sessionKey)) {
            logger.debug("Sub-agent detected, forcing single-agent mode to prevent recursion");
            return handleSingleAgentDefault(sessionKey, userContent, eventCallback);
        }

        AgentRole specifiedRole = extractSpecifiedRole(userContent);
        if (specifiedRole != null) {
            logger.info("Specified role detected: {}", specifiedRole.getDisplayName());
            return handleSingleAgentWithRole(sessionKey, userContent, specifiedRole, eventCallback);
        }

        logger.info("Single-agent mode (Agent can use spawn tool if needed)");
        return handleSingleAgentDefault(sessionKey, userContent, eventCallback);
    }

    private boolean isSubAgent(String sessionKey) {
        return sessionKey.startsWith("spawn-") || sessionKey.startsWith("subagent-");
    }

    private AgentRole extractSpecifiedRole(String userContent) {
        if (userContent == null || userContent.isEmpty()) {
            return null;
        }

        Matcher matcher = ROLE_PATTERN.matcher(userContent);
        if (matcher.find()) {
            String roleCode = matcher.group(2).toLowerCase();
            return AgentRole.fromCode(roleCode);
        }
        return null;
    }

    private String handleSingleAgentDefault(String sessionKey,
                                            String userContent,
                                            Consumer<ExecutionEvent> eventCallback) {
        return executeWithHarness(sessionKey, userContent, eventCallback, (taskInput, callback) -> {
            AgentLoop agent = agentRegistry.getOrCreateAgent(AgentRole.ASSISTANT, sessionKey);
            if (callback != null) {
                return agent.process(sessionKey, taskInput, callback);
            }
            return agent.process(sessionKey, taskInput);
        });
    }

    private String handleSingleAgentWithRole(String sessionKey,
                                             String userContent,
                                             AgentRole role,
                                             Consumer<ExecutionEvent> eventCallback) {
        return executeWithHarness(sessionKey, userContent, eventCallback, (taskInput, callback) -> {
            AgentLoop agent = agentRegistry.getOrCreateAgent(role, sessionKey);
            if (callback != null) {
                return agent.process(sessionKey, taskInput, role, callback);
            }
            return agent.process(sessionKey, taskInput, role);
        });
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentOrchestrator Status:\n");
        sb.append("  Mode: single-agent + explicit role/definition routing\n");
        sb.append("  ").append(agentRegistry.getPoolStatus().replace("\n", "\n  "));
        return sb.toString();
    }

    private String executeWithHarness(String sessionKey,
                                      String userContent,
                                      Consumer<ExecutionEvent> eventCallback,
                                      HarnessAction action) {
        String runId = AgentRunIds.newTopLevelRunId();
        TaskHarnessRun harnessRun = taskHarnessService.startRun(
                sessionKey,
                runId,
                userContent,
                eventCallback
        );
        TaskPlanningDecision planningDecision = taskPlanningPolicy.decide(userContent);
        harnessRun.setPlanningMode(planningDecision.mode(), planningDecision.reason());
        Consumer<ExecutionEvent> effectiveCallback = taskHarnessService.wrapEventCallback(harnessRun, eventCallback);
        if (effectiveCallback != null) {
            effectiveCallback.accept(new ExecutionEvent(
                    sessionKey,
                    ExecutionEvent.EventType.CUSTOM,
                    "Task harness run created",
                    java.util.Map.of(
                            "source", "task_harness",
                            "label", "run_created",
                            "runId", runId
                    ),
                    runId,
                    null,
                    "task_harness",
                    "Task Harness"
            ));
        }
        taskHarnessService.transition(
                harnessRun,
                TaskHarnessPhase.PLAN,
                "running",
                "route",
                "Routing task into orchestrator",
                java.util.Map.of(
                        "planningMode", planningDecision.mode().name(),
                        "planningReason", planningDecision.reason()
                ),
                effectiveCallback
        );

        AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
        AgentExecutionContext.ExecutionScope harnessScope = new AgentExecutionContext.ExecutionScope(
                sessionKey,
                effectiveCallback,
                runId,
                previousScope != null ? previousScope.runId() : null,
                previousScope != null ? previousScope.agentId() : "task_harness",
                previousScope != null ? previousScope.agentName() : "Task Harness",
                previousScope != null ? previousScope.definition() : null
        );
        AgentExecutionContext.setCurrentContext(harnessScope);

        try {
            String plannedTaskInput = applyPlanningGuidance(userContent, planningDecision);
            String currentResponse = action.run(plannedTaskInput, effectiveCallback);
            TaskHarnessVerificationResult currentResult =
                    taskHarnessService.verify(harnessRun, currentResponse, null, taskHarnessVerifier, effectiveCallback);

            if (currentResult.success()) {
                taskHarnessService.complete(harnessRun, true, currentResult.reason(), effectiveCallback);
                return currentResponse;
            }

            while (true) {
                TaskHarnessFailure failure = taskHarnessService.recordFailure(
                        harnessRun,
                        taskHarnessRepairPromptBuilder.classify(harnessRun, currentResponse, null),
                        effectiveCallback
                );

                if (!canAttemptRepair(harnessRun)) {
                    taskHarnessService.complete(harnessRun, false, currentResult.reason(), effectiveCallback);
                    return currentResponse;
                }

                currentResponse = attemptRepair(action, harnessRun, userContent, failure, effectiveCallback);
                currentResult =
                        taskHarnessService.verify(harnessRun, currentResponse, null, taskHarnessVerifier, effectiveCallback);

                if (currentResult.success()) {
                    taskHarnessService.complete(harnessRun, true, currentResult.reason(), effectiveCallback);
                    return currentResponse;
                }
            }
        } catch (Exception e) {
            logger.error("Harness execution failed for session {}", sessionKey, e);
            if (effectiveCallback != null) {
                effectiveCallback.accept(new ExecutionEvent(sessionKey, ExecutionEvent.EventType.ERROR,
                        "Error: " + e.getMessage()));
            }
            TaskHarnessVerificationResult failedResult =
                    taskHarnessService.verify(harnessRun, null, e, taskHarnessVerifier, effectiveCallback);
            taskHarnessService.recordFailure(
                    harnessRun,
                    taskHarnessRepairPromptBuilder.classify(harnessRun, null, e),
                    effectiveCallback
            );
            taskHarnessService.complete(harnessRun, false, failedResult.reason(), effectiveCallback);
            return "Error: " + e.getMessage();
        } finally {
            if (previousScope != null) {
                AgentExecutionContext.setCurrentContext(previousScope);
            } else {
                AgentExecutionContext.clear();
            }
        }
    }

    private String attemptRepair(HarnessAction action,
                                 TaskHarnessRun harnessRun,
                                 String originalInput,
                                 TaskHarnessFailure failure,
                                 Consumer<ExecutionEvent> effectiveCallback) throws Exception {
        int maxAttempts = taskHarnessRepairStrategy.maxAttempts(harnessRun);
        int attempt = harnessRun.incrementRepairAttempts();
        taskHarnessService.transition(
                harnessRun,
                TaskHarnessPhase.REPAIR,
                "running",
                "repair",
                "Starting repair attempt " + attempt,
                java.util.Map.of(
                        "attempt", attempt,
                        "maxAttempts", maxAttempts,
                        "reason", failure.reason(),
                        "kind", failure.kind().name(),
                        "failureType", taskHarnessRepairStrategy.failureType(harnessRun)
                ),
                effectiveCallback
        );

        String repairInput = taskHarnessRepairPromptBuilder.build(harnessRun, originalInput, failure, attempt);
        repairInput = applyPlanningGuidance(repairInput,
                new TaskPlanningDecision(harnessRun.getPlanningMode(), harnessRun.getPlanningReason()));
        return action.run(repairInput, effectiveCallback);
    }

    private boolean canAttemptRepair(TaskHarnessRun harnessRun) {
        return harnessRun.getRepairAttempts() < taskHarnessRepairStrategy.maxAttempts(harnessRun);
    }

    private String applyPlanningGuidance(String taskInput, TaskPlanningDecision planningDecision) {
        if (planningDecision == null || planningDecision.mode() == null) {
            return taskInput;
        }
        return switch (planningDecision.mode()) {
            case DIRECT -> taskInput;
            case PHASED -> """
                    [Runtime Planning Policy]
                    This is a phased task. Break the work into concrete stages, complete the stages in order, and do not stop at a plan or progress note. Only finish after delivering the requested artifact or result.

                    """ + taskInput;
            case WORKLIST -> """
                    [Runtime Planning Policy]
                    This is a worklist task with independent items.
                    1. First build the full subtask worklist with `subtasks(action='plan', ...)`.
                    2. Do not start executing items before the worklist exists.
                    3. Execute items one by one, preferably with `spawn(..., subtaskId='...')`.
                    4. Do not finish while pending subtasks remain.
                    5. If you are unsure about progress, call `subtasks(action='status')`.

                    """ + taskInput;
        };
    }

    @FunctionalInterface
    private interface HarnessAction {
        String run(String taskInput, Consumer<ExecutionEvent> callback) throws Exception;
    }
}

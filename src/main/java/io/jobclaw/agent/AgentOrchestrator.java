package io.jobclaw.agent;

import io.jobclaw.agent.checkpoint.TaskCheckpointService;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.completion.TaskCompletionController;
import io.jobclaw.agent.completion.TaskCompletionDecision;
import io.jobclaw.agent.experience.ExperienceGuidanceService;
import io.jobclaw.agent.learning.LearningCandidateService;
import io.jobclaw.agent.planning.TaskPlanningDecision;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.planning.TaskPlan;
import io.jobclaw.agent.planning.TaskPlanningPolicy;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.agent.workflow.WorkflowMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates the primary execution paths for the main agent, role-based agents,
 * and explicit AgentDefinition executions.
 */
@Component
public class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MIN_CONTINUE_PASSES = 3;
    private static final int MAX_CONTINUE_PASSES = 12;

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(浣滀负|鎵紨|use|as|role)[:锛歕\\s]*(绋嬪簭鍛?|鐮旂┒鍛?|浣滃|瀹℃煡鍛?|瑙勫垝甯?|娴嬭瘯鍛榺coder|researcher|writer|reviewer|planner|tester)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern JSON_PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PARAM_PATH_PATTERN = Pattern.compile("path\\s*[=:]\\s*['\"]?([^,'\"\\n}]+)");

    private final AgentRegistry agentRegistry;
    private final TaskHarnessService taskHarnessService;
    private final TaskHarnessVerifier taskHarnessVerifier;
    private final TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder;
    private final TaskHarnessRepairStrategy taskHarnessRepairStrategy;
    private final TaskPlanningPolicy taskPlanningPolicy;
    private final TaskCheckpointService taskCheckpointService;
    private final TaskCompletionController taskCompletionController;
    private final WorkflowMemoryService workflowMemoryService;
    private final LearningCandidateService learningCandidateService;
    private final ExperienceGuidanceService experienceGuidanceService;
    private final io.jobclaw.config.Config config;

    @Autowired
    public AgentOrchestrator(io.jobclaw.config.Config config,
                             AgentRegistry agentRegistry,
                             TaskHarnessService taskHarnessService,
                             TaskHarnessVerifier taskHarnessVerifier,
                             TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder,
                             TaskHarnessRepairStrategy taskHarnessRepairStrategy,
                             TaskPlanningPolicy taskPlanningPolicy,
                             TaskCheckpointService taskCheckpointService,
                             TaskCompletionController taskCompletionController,
                             WorkflowMemoryService workflowMemoryService,
                             LearningCandidateService learningCandidateService,
                             ExperienceGuidanceService experienceGuidanceService) {
        this.config = config;
        this.agentRegistry = agentRegistry;
        this.taskHarnessService = taskHarnessService;
        this.taskHarnessVerifier = taskHarnessVerifier;
        this.taskHarnessRepairPromptBuilder = taskHarnessRepairPromptBuilder;
        this.taskHarnessRepairStrategy = taskHarnessRepairStrategy;
        this.taskPlanningPolicy = taskPlanningPolicy;
        this.taskCheckpointService = taskCheckpointService;
        this.taskCompletionController = taskCompletionController;
        this.workflowMemoryService = workflowMemoryService;
        this.learningCandidateService = learningCandidateService;
        this.experienceGuidanceService = experienceGuidanceService;
        logger.info("AgentOrchestrator initialized");
    }

    public AgentOrchestrator(io.jobclaw.config.Config config,
                             AgentRegistry agentRegistry,
                             TaskHarnessService taskHarnessService,
                             TaskHarnessVerifier taskHarnessVerifier,
                             TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder,
                             TaskHarnessRepairStrategy taskHarnessRepairStrategy,
                             TaskPlanningPolicy taskPlanningPolicy) {
        this(config, agentRegistry, taskHarnessService, taskHarnessVerifier, taskHarnessRepairPromptBuilder,
                taskHarnessRepairStrategy, taskPlanningPolicy,
                new TaskCheckpointService(new io.jobclaw.agent.checkpoint.TaskCheckpointStore() {
                    @Override
                    public void save(io.jobclaw.agent.checkpoint.TaskCheckpoint checkpoint) {
                    }

                    @Override
                    public java.util.Optional<io.jobclaw.agent.checkpoint.TaskCheckpoint> latest(String sessionId) {
                        return java.util.Optional.empty();
                    }
                }),
                new TaskCompletionController(config),
                new WorkflowMemoryService(new io.jobclaw.agent.workflow.WorkflowMemoryStore() {
                    @Override
                    public java.util.List<io.jobclaw.agent.workflow.WorkflowRecipe> list() {
                        return java.util.List.of();
                    }

                    @Override
                    public void saveAll(java.util.List<io.jobclaw.agent.workflow.WorkflowRecipe> recipes) {
                    }
                }),
                new LearningCandidateService(new io.jobclaw.agent.learning.LearningCandidateStore() {
                    @Override
                    public java.util.List<io.jobclaw.agent.learning.LearningCandidate> list() {
                        return java.util.List.of();
                    }

                    @Override
                    public void saveAll(java.util.List<io.jobclaw.agent.learning.LearningCandidate> candidates) {
                    }
                }),
                new ExperienceGuidanceService(
                        new WorkflowMemoryService(new io.jobclaw.agent.workflow.WorkflowMemoryStore() {
                            @Override
                            public java.util.List<io.jobclaw.agent.workflow.WorkflowRecipe> list() {
                                return java.util.List.of();
                            }

                            @Override
                            public void saveAll(java.util.List<io.jobclaw.agent.workflow.WorkflowRecipe> recipes) {
                            }
                        }),
                        new io.jobclaw.agent.learning.LearningCandidateStore() {
                            @Override
                            public java.util.List<io.jobclaw.agent.learning.LearningCandidate> list() {
                                return java.util.List.of();
                            }

                            @Override
                            public void saveAll(java.util.List<io.jobclaw.agent.learning.LearningCandidate> candidates) {
                            }
                        },
                        null
                ));
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
        AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
        TaskPlan taskPlan = adaptPlanForRunScope(taskPlanningPolicy.decide(userContent), previousScope);
        TaskPlanningDecision planningDecision = new TaskPlanningDecision(taskPlan.planningMode(), taskPlan.reason());
        harnessRun.setPlanningMode(taskPlan.planningMode(), taskPlan.reason());
        harnessRun.setDoneDefinition(taskPlan.doneDefinition());
        String experienceGuidance = experienceGuidanceService.buildGuidance(
                userContent,
                taskPlan.planningMode(),
                taskPlan.doneDefinition()
        );
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
            String plannedTaskInput = applyWorkflowGuidance(
                    applyPlanningGuidance(userContent, planningDecision),
                    experienceGuidance
            );
            String currentResponse = action.run(plannedTaskInput, effectiveCallback);
            TaskHarnessVerificationResult currentResult =
                    taskHarnessService.verify(harnessRun, currentResponse, null, taskHarnessVerifier, effectiveCallback);
            TaskCompletionDecision decision =
                    taskCompletionController.evaluate(harnessRun, harnessRun.getDoneDefinition(), currentResponse, null);
            taskHarnessService.recordCompletionDecision(
                    harnessRun,
                    decision.status().name(),
                    decision.reason(),
                    decision.missingRequirements(),
                    effectiveCallback
            );
            int continuePasses = 0;
            int stalledContinuePasses = 0;
            int previousPendingSubtasks = Integer.MAX_VALUE;
            int previousCompletedSubtasks = harnessRun.getSubtasks().size() - harnessRun.getPendingSubtaskCount();
            String previousResponse = currentResponse;

            while (true) {
                if (decision.status() == TaskCompletionDecision.Status.COMPLETE) {
                    taskHarnessService.complete(harnessRun, true, decision.reason(), effectiveCallback);
                    workflowMemoryService.recordSuccess(harnessRun);
                    learningCandidateService.recordSuccessfulRun(harnessRun);
                    return formatCompletedResponse(harnessRun, currentResponse);
                }
                if (decision.status() == TaskCompletionDecision.Status.BLOCKED) {
                    taskHarnessService.complete(harnessRun, false, decision.reason(), effectiveCallback);
                    learningCandidateService.recordFailedRun(harnessRun, decision.reason());
                    return currentResponse;
                }
                if (decision.status() == TaskCompletionDecision.Status.CONTINUE) {
                    int pendingSubtasks = harnessRun.getPendingSubtaskCount();
                    int completedSubtasks = completedSubtaskCount(harnessRun);
                    boolean progressed = hasContinueProgress(
                            previousPendingSubtasks,
                            pendingSubtasks,
                            previousCompletedSubtasks,
                            completedSubtasks,
                            previousResponse,
                            currentResponse
                    );
                    stalledContinuePasses = progressed ? 0 : stalledContinuePasses + 1;
                    boolean canEscalateStalledContinue = !(harnessRun.hasTrackedSubtasks() && pendingSubtasks > 0);
                    if (continuePasses >= maxContinuePasses()
                            || (canEscalateStalledContinue && stalledContinuePasses >= 2)) {
                        decision = TaskCompletionDecision.repair(
                                "Pending work did not make progress, escalate to repair",
                                java.util.List.of("continue_stalled")
                        );
                    } else {
                        continuePasses++;
                        previousPendingSubtasks = pendingSubtasks;
                        previousCompletedSubtasks = completedSubtasks;
                        previousResponse = currentResponse;
                        taskHarnessService.transition(
                                harnessRun,
                                TaskHarnessPhase.OBSERVE,
                                "continue",
                                "continue_required",
                                decision.reason(),
                                java.util.Map.of(
                                        "pendingSubtasks", pendingSubtasks,
                                        "continuePass", continuePasses
                                ),
                                effectiveCallback
                        );
                        currentResponse = action.run(buildContinueInput(userContent, harnessRun, decision), effectiveCallback);
                        currentResult = taskHarnessService.verify(harnessRun, currentResponse, null, taskHarnessVerifier, effectiveCallback);
                        decision = taskCompletionController.evaluate(harnessRun, harnessRun.getDoneDefinition(), currentResponse, null);
                        taskHarnessService.recordCompletionDecision(
                                harnessRun,
                                decision.status().name(),
                                decision.reason(),
                                decision.missingRequirements(),
                                effectiveCallback
                        );
                        continue;
                    }
                }

                TaskHarnessFailure failure = taskHarnessService.recordFailure(
                        harnessRun,
                        taskHarnessRepairPromptBuilder.classify(harnessRun, currentResponse, null),
                        effectiveCallback
                );

                if (!canAttemptRepair(harnessRun)) {
                    taskHarnessService.complete(harnessRun, false, currentResult.reason(), effectiveCallback);
                    learningCandidateService.recordFailedRun(harnessRun, currentResult.reason());
                    return currentResponse;
                }

                currentResponse = attemptRepair(action, harnessRun, userContent, failure, effectiveCallback);
                currentResult =
                        taskHarnessService.verify(harnessRun, currentResponse, null, taskHarnessVerifier, effectiveCallback);
                decision = taskCompletionController.evaluate(harnessRun, harnessRun.getDoneDefinition(), currentResponse, null);
                taskHarnessService.recordCompletionDecision(
                        harnessRun,
                        decision.status().name(),
                        decision.reason(),
                        decision.missingRequirements(),
                        effectiveCallback
                );
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
            learningCandidateService.recordFailedRun(harnessRun, failedResult.reason());
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

    private String buildContinueInput(String originalInput,
                                      TaskHarnessRun harnessRun,
                                      TaskCompletionDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("Continue the existing task. Do not restate prior completed work as the final answer.\n");
        sb.append("Completion controller reason: ").append(decision.reason()).append("\n");
        if (!decision.missingRequirements().isEmpty()) {
            sb.append("Missing requirements:\n");
            for (String requirement : decision.missingRequirements()) {
                sb.append("- ").append(requirement).append("\n");
            }
        }
        if (harnessRun.getPlanningMode() == TaskPlanningMode.WORKLIST) {
            sb.append("If you are unsure, call `subtasks(action='status')` and continue processing remaining items.\n");
        }
        sb.append("\nOriginal task:\n").append(originalInput);
        return applyPlanningGuidance(sb.toString(),
                new TaskPlanningDecision(harnessRun.getPlanningMode(), harnessRun.getPlanningReason()));
    }

    private boolean canAttemptRepair(TaskHarnessRun harnessRun) {
        return harnessRun.getRepairAttempts() < taskHarnessRepairStrategy.maxAttempts(harnessRun);
    }

    private int maxContinuePasses() {
        return Math.max(MIN_CONTINUE_PASSES,
                Math.min(MAX_CONTINUE_PASSES, Math.max(1, config.getAgent().getMaxToolIterations())));
    }

    private TaskPlan adaptPlanForRunScope(TaskPlan taskPlan, AgentExecutionContext.ExecutionScope previousScope) {
        if (taskPlan == null || taskPlan.doneDefinition() == null) {
            return taskPlan;
        }
        if (previousScope == null || previousScope.runId() == null || previousScope.runId().isBlank()) {
            return taskPlan;
        }
        DoneDefinition done = taskPlan.doneDefinition();
        if (!done.requiresWorklist()) {
            return taskPlan;
        }
        DoneDefinition childDone = new DoneDefinition(
                TaskPlanningMode.DIRECT,
                done.deliveryType(),
                done.requiredArtifacts(),
                done.requiredPhases(),
                false,
                done.requiresFinalSummary(),
                done.acceptOptionalFollowUp(),
                done.completionRules()
        );
        return new TaskPlan(
                TaskPlanningMode.DIRECT,
                childDone,
                taskPlan.reason() + "_child_contract"
        );
    }

    private String applyWorkflowGuidance(String taskInput, String experienceGuidance) {
        if (experienceGuidance == null || experienceGuidance.isBlank()) {
            return taskInput;
        }
        return experienceGuidance + "\n\n" + taskInput;
    }

    private int completedSubtaskCount(TaskHarnessRun harnessRun) {
        return (int) harnessRun.getSubtasks().stream()
                .filter(subtask -> subtask.status() == TaskHarnessSubtaskStatus.COMPLETED)
                .count();
    }

    private boolean hasContinueProgress(int previousPendingSubtasks,
                                        int pendingSubtasks,
                                        int previousCompletedSubtasks,
                                        int completedSubtasks,
                                        String previousResponse,
                                        String currentResponse) {
        if (pendingSubtasks < previousPendingSubtasks) {
            return true;
        }
        if (completedSubtasks > previousCompletedSubtasks) {
            return true;
        }
        String previous = previousResponse == null ? "" : previousResponse.trim();
        String current = currentResponse == null ? "" : currentResponse.trim();
        return !current.isBlank() && !current.equals(previous);
    }

    private String formatCompletedResponse(TaskHarnessRun harnessRun, String currentResponse) {
        if (harnessRun == null || harnessRun.getDoneDefinition() == null) {
            return currentResponse;
        }
        DeliveryType deliveryType = harnessRun.getDoneDefinition().deliveryType();
        if (deliveryType != DeliveryType.FILE_ARTIFACT && deliveryType != DeliveryType.PATCH) {
            return currentResponse;
        }
        if (!readsLikeProcessNarration(currentResponse)) {
            return currentResponse;
        }
        List<String> artifactPaths = resolveArtifactPaths(harnessRun);
        if (artifactPaths.isEmpty()) {
            return "已完成。";
        }
        String prefix = deliveryType == DeliveryType.PATCH ? "已完成，已更新文件：" : "已完成，输出文件：";
        if (artifactPaths.size() == 1) {
            return prefix + artifactPaths.get(0);
        }
        return prefix + "\n- " + String.join("\n- ", artifactPaths);
    }

    private boolean readsLikeProcessNarration(String response) {
        if (response == null || response.isBlank()) {
            return true;
        }
        String normalized = response.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("我来帮你")
                || normalized.contains("首先")
                || normalized.contains("让我")
                || normalized.contains("继续分析")
                || normalized.contains("继续处理")
                || normalized.contains("i will")
                || normalized.contains("let me");
    }

    private List<String> resolveArtifactPaths(TaskHarnessRun harnessRun) {
        Set<String> paths = new LinkedHashSet<>();
        for (TaskHarnessStep step : harnessRun.getSteps()) {
            Object eventType = step.metadata().get("eventType");
            if (!"TOOL_START".equals(eventType)) {
                continue;
            }
            String toolName = String.valueOf(step.metadata().getOrDefault("toolName", "")).toLowerCase(Locale.ROOT);
            if (!toolName.equals("write_file") && !toolName.equals("edit_file") && !toolName.equals("append_file")) {
                continue;
            }
            String request = String.valueOf(step.metadata().getOrDefault("request", ""));
            collectPaths(paths, JSON_PATH_PATTERN.matcher(request));
            collectPaths(paths, PARAM_PATH_PATTERN.matcher(request));
        }
        return List.copyOf(paths);
    }

    private void collectPaths(Set<String> paths, Matcher matcher) {
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path != null && !path.isBlank()) {
                paths.add(path.trim());
            }
        }
    }

    private String applyPlanningGuidance(String taskInput, TaskPlanningDecision planningDecision) {
        if (planningDecision == null || planningDecision.mode() == null) {
            return taskInput;
        }
        String resumeGuidance = AgentExecutionContext.getCurrentScope() == null
                ? ""
                : taskCheckpointService.latestResumable(
                        AgentExecutionContext.getCurrentScope().sessionKey(),
                        taskInput,
                        planningDecision.mode()
                ).map(taskCheckpointService::buildResumeGuidance)
                .orElse("");
        return switch (planningDecision.mode()) {
            case DIRECT -> taskInput;
            case PHASED -> """
                    [Runtime Planning Policy]
                    This is a phased task. Break the work into concrete stages, complete the stages in order, and do not stop at a plan or progress note. Only finish after delivering the requested artifact or result.

                    """ + (resumeGuidance.isBlank() ? "" : resumeGuidance + "\n\n") + taskInput;
            case WORKLIST -> """
                    [Runtime Planning Policy]
                    This is a worklist task with independent items.
                    1. First build the full subtask worklist with `subtasks(action='plan', ...)`.
                    2. Do not start executing items before the worklist exists.
                    3. Execute items one by one, preferably with `spawn(..., subtaskId='...')`.
                    4. Do not finish while pending subtasks remain.
                    5. If you are unsure about progress, call `subtasks(action='status')`.

                    """ + (resumeGuidance.isBlank() ? "" : resumeGuidance + "\n\n") + taskInput;
        };
    }

    @FunctionalInterface
    private interface HarnessAction {
        String run(String taskInput, Consumer<ExecutionEvent> callback) throws Exception;
    }
}

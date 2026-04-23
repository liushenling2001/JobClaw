package io.jobclaw.agent;

import io.jobclaw.agent.checkpoint.TaskCheckpointService;
import io.jobclaw.agent.checkpoint.TaskCheckpoint;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.completion.TaskCompletionController;
import io.jobclaw.agent.completion.TaskCompletionDecision;
import io.jobclaw.agent.experience.ExperienceGuidanceService;
import io.jobclaw.agent.learning.LearningCandidateService;
import io.jobclaw.agent.planning.AgentPlanService;
import io.jobclaw.agent.planning.TaskPlanningDecision;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.planning.TaskPlan;
import io.jobclaw.agent.planning.TaskPlanningPolicy;
import io.jobclaw.agent.planning.PlanReviewAction;
import io.jobclaw.agent.planning.PlanReviewController;
import io.jobclaw.agent.planning.PlanReviewDecision;
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
    private final TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder;
    private final TaskHarnessRepairStrategy taskHarnessRepairStrategy;
    private final TaskPlanningPolicy taskPlanningPolicy;
    private final AgentPlanService agentPlanService;
    private final PlanReviewController planReviewController;
    private final TaskCheckpointService taskCheckpointService;
    private final TaskCompletionController taskCompletionController;
    private final WorkflowMemoryService workflowMemoryService;
    private final LearningCandidateService learningCandidateService;
    private final ExperienceGuidanceService experienceGuidanceService;
    private final ToolsetRuntimePolicy toolsetRuntimePolicy;
    private final io.jobclaw.config.Config config;

    @Autowired
    public AgentOrchestrator(io.jobclaw.config.Config config,
                              AgentRegistry agentRegistry,
                              TaskHarnessService taskHarnessService,
                              TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder,
                              TaskHarnessRepairStrategy taskHarnessRepairStrategy,
                             TaskPlanningPolicy taskPlanningPolicy,
                             AgentPlanService agentPlanService,
                             PlanReviewController planReviewController,
                             TaskCheckpointService taskCheckpointService,
                             TaskCompletionController taskCompletionController,
                             WorkflowMemoryService workflowMemoryService,
                             LearningCandidateService learningCandidateService,
                             ExperienceGuidanceService experienceGuidanceService,
                             ToolsetRuntimePolicy toolsetRuntimePolicy) {
        this.config = config;
        this.agentRegistry = agentRegistry;
        this.taskHarnessService = taskHarnessService;
        this.taskHarnessRepairPromptBuilder = taskHarnessRepairPromptBuilder;
        this.taskHarnessRepairStrategy = taskHarnessRepairStrategy;
        this.taskPlanningPolicy = taskPlanningPolicy;
        this.agentPlanService = agentPlanService;
        this.planReviewController = planReviewController;
        this.taskCheckpointService = taskCheckpointService;
        this.taskCompletionController = taskCompletionController;
        this.workflowMemoryService = workflowMemoryService;
        this.learningCandidateService = learningCandidateService;
        this.experienceGuidanceService = experienceGuidanceService;
        this.toolsetRuntimePolicy = toolsetRuntimePolicy != null ? toolsetRuntimePolicy : new ToolsetRuntimePolicy();
        logger.info("AgentOrchestrator initialized");
    }

    public AgentOrchestrator(io.jobclaw.config.Config config,
                             AgentRegistry agentRegistry,
                             TaskHarnessService taskHarnessService,
                             TaskHarnessRepairPromptBuilder taskHarnessRepairPromptBuilder,
                             TaskHarnessRepairStrategy taskHarnessRepairStrategy,
                             TaskPlanningPolicy taskPlanningPolicy) {
        this(config, agentRegistry, taskHarnessService, taskHarnessRepairPromptBuilder,
                taskHarnessRepairStrategy, taskPlanningPolicy, new AgentPlanService(taskPlanningPolicy), new PlanReviewController(),
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
                ),
                new ToolsetRuntimePolicy());
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
        TaskCheckpoint resumeCheckpoint = taskCheckpointService.latestForResumeRequest(sessionKey, userContent)
                .orElse(null);
        String taskInput = resumeCheckpoint != null && resumeCheckpoint.taskInput() != null && !resumeCheckpoint.taskInput().isBlank()
                ? resumeCheckpoint.taskInput()
                : userContent;
        TaskHarnessRun harnessRun = taskHarnessService.startRun(
                sessionKey,
                runId,
                taskInput,
                eventCallback
        );
        AgentExecutionContext.ExecutionScope previousScope = AgentExecutionContext.getCurrentScope();
        TaskPlan taskPlan = adaptPlanForRunScope(createInitialPlan(sessionKey, taskInput), previousScope);
        TaskPlanningDecision planningDecision = new TaskPlanningDecision(taskPlan.planningMode(), taskPlan.reason());
        harnessRun.setPlanningMode(taskPlan.planningMode(), taskPlan.reason());
        harnessRun.setDoneDefinition(taskPlan.doneDefinition());
        String experienceGuidance = shouldApplyExperienceGuidance(previousScope)
                ? experienceGuidanceService.buildGuidance(
                taskInput,
                taskPlan.planningMode(),
                taskPlan.doneDefinition()
        )
                : "";
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
        taskHarnessService.initializePlan(harnessRun, taskPlan.steps(), effectiveCallback);
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
                    applyPlanningGuidance(taskInput, planningDecision, harnessRun),
                    experienceGuidance
            );
            plannedTaskInput = applyStepRuntimeGuidance(plannedTaskInput, harnessRun);
            String currentResponse = runWithRuntimeToolset(action, harnessRun, plannedTaskInput, effectiveCallback);
            TaskCompletionDecision decision =
                    taskCompletionController.evaluate(harnessRun, harnessRun.getDoneDefinition(), currentResponse, null);
            taskHarnessService.recordCompletionDecision(
                    harnessRun,
                    decision.status().name(),
                    decision.reason(),
                    decision.missingRequirements(),
                    effectiveCallback
            );
            recordStepOutcomeIfUseful(harnessRun, decision, currentResponse, effectiveCallback);
            int continuePasses = 0;
            while (true) {
                if (decision.status() == TaskCompletionDecision.Status.COMPLETE) {
                    taskHarnessService.complete(harnessRun, true, decision.reason(), effectiveCallback);
                    workflowMemoryService.recordSuccess(harnessRun);
                    learningCandidateService.recordSuccessfulRun(harnessRun);
                    return publishAndReturnFinal(sessionKey, effectiveCallback, formatCompletedResponse(harnessRun, currentResponse));
                }
                if (decision.status() == TaskCompletionDecision.Status.BLOCKED) {
                    taskHarnessService.complete(harnessRun, false, decision.reason(), effectiveCallback);
                    learningCandidateService.recordFailedRun(harnessRun, decision.reason());
                    return publishAndReturnFinal(sessionKey, effectiveCallback, currentResponse);
                }
                if (decision.status() == TaskCompletionDecision.Status.CONTINUE) {
                    int pendingSubtasks = harnessRun.getPendingSubtaskCount();
                    if (continuePasses >= maxContinuePasses()) {
                        decision = TaskCompletionDecision.planReview(
                                "Continue limit reached; review the active plan before repair",
                                java.util.List.of("continue_limit_reached")
                        );
                    } else {
                        continuePasses++;
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
                        currentResponse = runWithRuntimeToolset(
                                action,
                                harnessRun,
                                buildContinueInput(taskInput, harnessRun, decision),
                                effectiveCallback
                        );
                        decision = taskCompletionController.evaluate(harnessRun, harnessRun.getDoneDefinition(), currentResponse, null);
                        taskHarnessService.recordCompletionDecision(
                                harnessRun,
                                decision.status().name(),
                                decision.reason(),
                                decision.missingRequirements(),
                                effectiveCallback
                        );
                        recordStepOutcomeIfUseful(harnessRun, decision, currentResponse, effectiveCallback);
                        continue;
                    }
                }

                TaskHarnessFailure failure = taskHarnessService.recordFailure(
                        harnessRun,
                        taskHarnessRepairPromptBuilder.classify(harnessRun, currentResponse, null),
                        effectiveCallback
                );
                PlanReviewDecision planReviewDecision = planReviewController.evaluate(harnessRun, failure, currentResponse);
                taskHarnessService.recordPlanReviewDecision(harnessRun, planReviewDecision, effectiveCallback);
                if (planReviewDecision.action() == PlanReviewAction.BLOCKED) {
                    taskHarnessService.complete(harnessRun, false, planReviewDecision.reason(), effectiveCallback);
                    learningCandidateService.recordFailedRun(harnessRun, planReviewDecision.reason());
                    return publishAndReturnFinal(sessionKey, effectiveCallback, currentResponse);
                }
                if (planReviewDecision.action() != PlanReviewAction.KEEP_PLAN
                        && harnessRun.getPlanReviewAttempts() <= 3) {
                    taskHarnessService.transition(
                            harnessRun,
                            TaskHarnessPhase.PLAN,
                            "continue",
                            "plan_review_continue",
                            "Continuing with plan review adjustment: " + planReviewDecision.action(),
                            java.util.Map.of("action", planReviewDecision.action().name()),
                            effectiveCallback
                    );
                    currentResponse = runWithRuntimeToolset(
                            action,
                            harnessRun,
                            buildPlanReviewInput(taskInput, harnessRun, failure, planReviewDecision),
                            effectiveCallback
                    );
                    decision = taskCompletionController.evaluate(harnessRun, harnessRun.getDoneDefinition(), currentResponse, null);
                    taskHarnessService.recordCompletionDecision(
                            harnessRun,
                            decision.status().name(),
                            decision.reason(),
                            decision.missingRequirements(),
                            effectiveCallback
                    );
                    recordStepOutcomeIfUseful(harnessRun, decision, currentResponse, effectiveCallback);
                    continue;
                }

                if (!canAttemptRepair(harnessRun)) {
                    taskHarnessService.complete(harnessRun, false, decision.reason(), effectiveCallback);
                    learningCandidateService.recordFailedRun(harnessRun, decision.reason());
                    return publishAndReturnFinal(sessionKey, effectiveCallback, currentResponse);
                }

                currentResponse = attemptRepair(action, harnessRun, taskInput, failure, effectiveCallback);
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
            taskHarnessService.recordFailure(
                    harnessRun,
                    taskHarnessRepairPromptBuilder.classify(harnessRun, null, e),
                    effectiveCallback
            );
            String failureReason = e.getMessage() != null ? e.getMessage() : e.toString();
            taskHarnessService.complete(harnessRun, false, failureReason, effectiveCallback);
            learningCandidateService.recordFailedRun(harnessRun, failureReason);
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
                new TaskPlanningDecision(harnessRun.getPlanningMode(), harnessRun.getPlanningReason()),
                harnessRun);
        repairInput = applyStepRuntimeGuidance(repairInput, harnessRun);
        return runWithRuntimeToolset(action, harnessRun, repairInput, effectiveCallback);
    }

    private String runWithRuntimeToolset(HarnessAction action,
                                         TaskHarnessRun harnessRun,
                                         String taskInput,
                                         Consumer<ExecutionEvent> effectiveCallback) throws Exception {
        Set<String> previousRequiredTools = AgentExecutionContext.getRuntimeRequiredToolNames();
        Set<String> requiredTools = toolsetRuntimePolicy.requiredToolsFor(harnessRun);
        AgentExecutionContext.setRuntimeRequiredToolNames(requiredTools);
        try {
            logger.debug("Runtime required tools for run {}: {}", harnessRun.getRunId(), requiredTools);
            taskHarnessService.transition(
                    harnessRun,
                    TaskHarnessPhase.ACT,
                    "running",
                    "agent_action",
                    "Executing current plan step",
                    java.util.Map.of("requiredTools", String.join(",", requiredTools)),
                    effectiveCallback
            );
            return action.run(taskInput, suppressIntermediateFinalResponse(effectiveCallback));
        } finally {
            if (previousRequiredTools.isEmpty()) {
                AgentExecutionContext.clearRuntimeRequiredToolNames();
            } else {
                AgentExecutionContext.setRuntimeRequiredToolNames(previousRequiredTools);
            }
        }
    }

    private Consumer<ExecutionEvent> suppressIntermediateFinalResponse(Consumer<ExecutionEvent> delegate) {
        if (delegate == null) {
            return null;
        }
        return event -> {
            if (event != null && event.getType() == ExecutionEvent.EventType.FINAL_RESPONSE) {
                return;
            }
            delegate.accept(event);
        };
    }

    private String publishAndReturnFinal(String sessionKey,
                                         Consumer<ExecutionEvent> effectiveCallback,
                                         String response) {
        if (effectiveCallback != null) {
            effectiveCallback.accept(new ExecutionEvent(
                    sessionKey,
                    ExecutionEvent.EventType.FINAL_RESPONSE,
                    response != null ? response : ""
            ));
        }
        return response;
    }

    private String buildContinueInput(String originalInput,
                                      TaskHarnessRun harnessRun,
                                      TaskCompletionDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("Continue the existing task. Do not restate prior completed work as the final answer.\n");
        sb.append("Use the active plan as the only task contract. Do not change the task type or create a worklist unless the active plan explicitly requires one.\n");
        sb.append("Reuse completed step evidence and artifact paths from the plan state. Do not repeat completed reads or completed subtasks unless the plan requires a targeted outcome check.\n");
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
        String snapshot = taskHarnessService.buildPlanExecutionSnapshot(harnessRun);
        if (!snapshot.isBlank()) {
            sb.append("\n").append(snapshot).append("\n");
        }
        sb.append("\nOriginal task:\n").append(originalInput);
        String input = applyPlanningGuidance(sb.toString(),
                new TaskPlanningDecision(harnessRun.getPlanningMode(), harnessRun.getPlanningReason()),
                harnessRun);
        return applyStepRuntimeGuidance(input, harnessRun);
    }

    private String buildPlanReviewInput(String originalInput,
                                        TaskHarnessRun harnessRun,
                                        TaskHarnessFailure failure,
                                        PlanReviewDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Plan Review Adjustment]\n");
        sb.append("The previous attempt should not enter blind repair yet. Adjust execution according to the active plan review decision.\n");
        sb.append("Preserve completed work. If the plan was wrong, revise only the missing or invalid part and reuse existing evidence/artifacts.\n");
        sb.append("Do not reclassify the task from the original text. The active plan contract is the source of truth unless this plan review explicitly changes it.\n");
        sb.append("Action: ").append(decision.action()).append("\n");
        sb.append("Reason: ").append(decision.reason()).append("\n");
        if (!decision.instructions().isEmpty()) {
            sb.append("Instructions:\n");
            for (String instruction : decision.instructions()) {
                sb.append("- ").append(instruction).append("\n");
            }
        }
        sb.append("\nFailure evidence:\n");
        sb.append("kind: ").append(failure.kind()).append("\n");
        sb.append("reason: ").append(failure.reason()).append("\n");
        if (failure.evidence() != null && !failure.evidence().isBlank()) {
            sb.append("evidence: ").append(failure.evidence()).append("\n");
        }
        String snapshot = taskHarnessService.buildPlanExecutionSnapshot(harnessRun);
        if (!snapshot.isBlank()) {
            sb.append("\n").append(snapshot).append("\n");
        }
        sb.append("\nOriginal task:\n").append(originalInput);
        String input = applyPlanningGuidance(sb.toString(),
                new TaskPlanningDecision(harnessRun.getPlanningMode(), harnessRun.getPlanningReason()),
                harnessRun);
        return applyStepRuntimeGuidance(input, harnessRun);
    }

    private boolean canAttemptRepair(TaskHarnessRun harnessRun) {
        return harnessRun.getRepairAttempts() < taskHarnessRepairStrategy.maxAttempts(harnessRun);
    }

    private int maxContinuePasses() {
        return Math.max(MIN_CONTINUE_PASSES,
                Math.min(MAX_CONTINUE_PASSES, Math.max(1, config.getAgent().getMaxToolIterations())));
    }

    private TaskPlan createInitialPlan(String sessionKey, String userContent) {
        try {
            AgentLoop plannerAgent = agentRegistry.getOrCreateAgent(AgentRole.ASSISTANT, sessionKey);
            TaskPlan plan = agentPlanService.plan(plannerAgent, userContent);
            if (plan != null) {
                return plan;
            }
        } catch (Exception e) {
            logger.warn("Agent planner failed, falling back to policy planner: {}", e.getMessage());
        }
        return taskPlanningPolicy.decide(userContent);
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
                done.requiredArtifactDirectories(),
                done.requiredPhases(),
                false,
                done.requiresFinalSummary(),
                done.acceptOptionalFollowUp(),
                done.completionRules()
        );
        return new TaskPlan(
                TaskPlanningMode.DIRECT,
                childDone,
                taskPlan.reason() + "_child_contract",
                taskPlan.steps()
        );
    }

    private boolean shouldApplyExperienceGuidance(AgentExecutionContext.ExecutionScope previousScope) {
        return previousScope == null
                || previousScope.parentRunId() == null
                || previousScope.parentRunId().isBlank();
    }

    private String applyWorkflowGuidance(String taskInput, String experienceGuidance) {
        if (experienceGuidance == null || experienceGuidance.isBlank()) {
            return taskInput;
        }
        return experienceGuidance + "\n\n" + taskInput;
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

    private String applyPlanningGuidance(String taskInput, TaskPlanningDecision planningDecision, TaskHarnessRun harnessRun) {
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
        String planSnapshot = taskHarnessService.buildPlanExecutionSnapshot(harnessRun);
        String planState = planSnapshot.isBlank() ? "" : planSnapshot + "\n\n";
        return switch (planningDecision.mode()) {
            case DIRECT -> taskInput;
            case PHASED -> """
                    [Runtime Planning Policy]
                    This is a phased task. Break the work into concrete stages, complete the stages in order, and do not stop at a plan or progress note. Only finish after delivering the requested artifact or result.
                    Do not create a worklist unless the current plan state or the user explicitly requires one. The harness will not require subtasks for a phased task.

                    """ + (resumeGuidance.isBlank() ? "" : resumeGuidance + "\n\n") + planState + taskInput;
            case WORKLIST -> """
                    [Runtime Planning Policy]
                    This is a worklist task with independent items.
                    1. First build the full subtask worklist with `subtasks(action='plan', ...)`.
                    2. Do not start executing items before the worklist exists.
                    3. Execute items one by one, preferably with `spawn(..., subtaskId='...')`.
                    4. Do not finish while pending subtasks remain.
                    5. If you are unsure about progress, call `subtasks(action='status')`.

                    """ + (resumeGuidance.isBlank() ? "" : resumeGuidance + "\n\n") + planState + taskInput;
        };
    }

    private String applyStepRuntimeGuidance(String taskInput, TaskHarnessRun harnessRun) {
        if (harnessRun == null || harnessRun.getPlanningMode() == TaskPlanningMode.DIRECT) {
            return taskInput;
        }
        String stepRuntimeContext = taskHarnessService.buildStepRuntimeContext(harnessRun);
        if (stepRuntimeContext == null || stepRuntimeContext.isBlank()) {
            return taskInput;
        }
        return stepRuntimeContext + "\n\n[Task Input]\n" + taskInput;
    }

    private void recordStepOutcomeIfUseful(TaskHarnessRun harnessRun,
                                           TaskCompletionDecision decision,
                                           String response,
                                           Consumer<ExecutionEvent> effectiveCallback) {
        if (harnessRun == null
                || harnessRun.getPlanningMode() == TaskPlanningMode.DIRECT
                || harnessRun.getPlanExecutionState() == null
                || harnessRun.getPlanExecutionState().isEmpty()
                || decision == null) {
            return;
        }
        if (decision.status() == TaskCompletionDecision.Status.BLOCKED
                || decision.status() == TaskCompletionDecision.Status.REPAIR) {
            return;
        }
        if (response == null || response.isBlank() || response.startsWith("Error:")) {
            return;
        }
        taskHarnessService.completeCurrentPlanStep(harnessRun, response, effectiveCallback);
    }

    @FunctionalInterface
    private interface HarnessAction {
        String run(String taskInput, Consumer<ExecutionEvent> callback) throws Exception;
    }
}

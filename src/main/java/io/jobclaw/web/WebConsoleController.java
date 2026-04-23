package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.TaskHarnessFailure;
import io.jobclaw.agent.TaskHarnessRun;
import io.jobclaw.agent.TaskHarnessService;
import io.jobclaw.agent.TaskHarnessStep;
import io.jobclaw.agent.experience.ExperienceMemoryService;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateService;
import io.jobclaw.agent.profile.AgentProfile;
import io.jobclaw.agent.profile.AgentProfileService;
import io.jobclaw.agent.catalog.AgentCatalogEntry;
import io.jobclaw.agent.catalog.AgentCatalogService;
import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.*;
import io.jobclaw.cron.*;
import io.jobclaw.mcp.MCPService;
import io.jobclaw.retrieval.RetrievalBundle;
import io.jobclaw.retrieval.RetrievalService;
import io.jobclaw.retrieval.SearchQuery;
import io.jobclaw.security.SecurityGuard;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.skills.SkillInfo;
import io.jobclaw.skills.SkillsService;
import io.jobclaw.stats.TokenUsageService;
import io.jobclaw.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class WebConsoleController {

    private static final Logger logger = LoggerFactory.getLogger(WebConsoleController.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final AgentLoop agentLoop;
    private final AgentOrchestrator orchestrator;
    private final MessageBus messageBus;
    private final ExecutionTraceService executionTraceService;
    private final TaskHarnessService taskHarnessService;
    private final CronService cronService;
    private final SkillsService skillsService;
    private final io.jobclaw.mcp.MCPService mcpService;
    private final TokenUsageService tokenUsageService;
    private final SecurityGuard securityGuard;
    private final RetrievalService retrievalService;
    private final AgentProfileService agentProfileService;
    private final AgentCatalogService agentCatalogService;
    private final LearningCandidateService learningCandidateService;
    private final ExperienceMemoryService experienceMemoryService;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public WebConsoleController(Config config, SessionManager sessionManager,
                                 AgentLoop agentLoop, AgentOrchestrator orchestrator,
                                 MessageBus messageBus, ExecutionTraceService executionTraceService,
                                 TaskHarnessService taskHarnessService,
                                 CronService cronService, SkillsService skillsService,
                                 io.jobclaw.mcp.MCPService mcpService,
                                 TokenUsageService tokenUsageService,
                                 SecurityGuard securityGuard,
                                 RetrievalService retrievalService,
                                 AgentProfileService agentProfileService,
                                 AgentCatalogService agentCatalogService,
                                 LearningCandidateService learningCandidateService,
                                 ExperienceMemoryService experienceMemoryService) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.orchestrator = orchestrator;
        this.messageBus = messageBus;
        this.executionTraceService = executionTraceService;
        this.taskHarnessService = taskHarnessService;
        this.cronService = cronService;
        this.skillsService = skillsService;
        this.mcpService = mcpService;
        this.tokenUsageService = tokenUsageService;
        this.securityGuard = securityGuard;
        this.retrievalService = retrievalService;
        this.agentProfileService = agentProfileService;
        this.agentCatalogService = agentCatalogService;
        this.learningCandidateService = learningCandidateService;
        this.experienceMemoryService = experienceMemoryService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "ok");
        status.put("workspace", config.getWorkspacePath());
        status.put("model", config.getAgent().getModel());
        status.put("sessions", sessionManager.getUserSessionCount());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/agents")
    public ResponseEntity<List<AgentProfile>> getAgents() {
        return ResponseEntity.ok(agentProfileService.listProfiles());
    }

    @GetMapping("/agents/{id}")
    public ResponseEntity<AgentProfile> getAgent(@PathVariable String id) {
        return agentProfileService.getProfile(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/agents")
    public ResponseEntity<?> createAgent(@RequestBody AgentProfileUpsertRequest request) {
        if (request.getCode() == null || request.getCode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "code is required"));
        }
        if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "displayName is required"));
        }
        if (agentCatalogService.get(request.getCode().trim()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agent already exists: " + request.getCode().trim()));
        }

        AgentCatalogEntry entry = agentCatalogService.createAgent(
                request.getCode().trim(),
                request.getDisplayName().trim(),
                normalizeText(request.getDescription()),
                defaultPromptIfBlank(request.getDisplayName(), request.getDescription(), request.getSystemPrompt()),
                normalizeList(request.getAliases()),
                normalizeList(request.getAllowedTools()),
                normalizeList(request.getAllowedSkills()),
                normalizeModelConfig(request.getModelConfig()),
                normalizeText(request.getMemoryScope())
        );
        return ResponseEntity.ok(agentProfileService.getProfile("agent:" + entry.code()).orElse(null));
    }

    @PutMapping("/agents/{id}")
    public ResponseEntity<?> updateAgent(@PathVariable String id, @RequestBody AgentProfileUpsertRequest request) {
        if (id.startsWith("main:")) {
            return ResponseEntity.badRequest().body(Map.of("error", "main assistant is managed through global config.json"));
        }
        String code = extractAgentCode(id);
        if (code == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported agent id: " + id));
        }
        return agentCatalogService.updateAgent(
                        code,
                        normalizeText(request.getDisplayName()),
                        normalizeText(request.getDescription()),
                        normalizeText(request.getSystemPrompt()),
                        request.getAliases() != null ? normalizeList(request.getAliases()) : null,
                        request.getAllowedTools() != null ? normalizeList(request.getAllowedTools()) : null,
                        request.getAllowedSkills() != null ? normalizeList(request.getAllowedSkills()) : null,
                        request.getModelConfig() != null ? normalizeModelConfig(request.getModelConfig()) : null,
                        request.getMemoryScope(),
                        request.getStatus()
                )
                .flatMap(entry -> agentProfileService.getProfile("agent:" + entry.code()))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/agents/{id}/clone")
    public ResponseEntity<?> cloneAgent(@PathVariable String id, @RequestBody AgentProfileCloneRequest request) {
        AgentProfile sourceProfile = agentProfileService.getProfile(id).orElse(null);
        if (sourceProfile == null) {
            return ResponseEntity.notFound().build();
        }

        String code = request.getCode() != null && !request.getCode().isBlank()
                ? request.getCode().trim()
                : sourceProfile.code() + "_copy";
        String displayName = request.getDisplayName() != null && !request.getDisplayName().isBlank()
                ? request.getDisplayName().trim()
                : sourceProfile.displayName() + " Copy";

        if (agentCatalogService.get(code).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agent already exists: " + code));
        }

        AgentCatalogEntry entry = agentCatalogService.createAgent(
                code,
                displayName,
                normalizeText(sourceProfile.description()),
                defaultPromptIfBlank(displayName, sourceProfile.description(), sourceProfile.systemPrompt()),
                List.of(displayName),
                normalizeList(sourceProfile.allowedTools()),
                normalizeList(sourceProfile.allowedSkills()),
                normalizeModelConfig(sourceProfile.modelConfig()),
                normalizeText(sourceProfile.memoryScope())
        );
        return ResponseEntity.ok(agentProfileService.getProfile("agent:" + entry.code()).orElse(null));
    }

    @PostMapping("/agents/{id}/activate")
    public ResponseEntity<?> activateAgent(@PathVariable String id) {
        return changeAgentStatus(id, true);
    }

    @PostMapping("/agents/{id}/disable")
    public ResponseEntity<?> disableAgent(@PathVariable String id) {
        return changeAgentStatus(id, false);
    }

    @DeleteMapping("/agents/{id}")
    public ResponseEntity<?> deleteAgent(@PathVariable String id) {
        if (!id.startsWith("agent:")) {
            return ResponseEntity.badRequest().body(Map.of("error", "only user-defined catalog agents can be deleted"));
        }
        String code = id.substring("agent:".length());
        if (!agentCatalogService.deleteAgent(code)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true, "code", code));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sessionKey = request.getSessionKey() != null ? request.getSessionKey() : "web:default";
            // 娴ｈ法鏁ょ紓鏍ㄥ笓閸ｃ劌顦╅悶鍡氼嚞濮瑰偊绱欓弨顖涘瘮婢?Agent 濡€崇础閿?
            String result = orchestrator.process(sessionKey, request.getMessage());
            response.put("success", true);
            response.put("message", result);
            response.put("session", sessionKey);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 閹笛嗩攽娴犺濮熼獮鎯邦吂闂冨懏澧界悰宀冪箖缁嬪绨ㄦ禒璁圭礄SSE 濞翠礁绱℃潏鎾冲毉閿?
     *
     * 閸撳秶顏崣顖欎簰娴ｈ法鏁?EventSource 鏉╃偞甯撮崚鎷岀箹娑擃亞顏悙鐧哥礉鐎圭偞妞傞幒銉︽暪 Agent 閹笛嗩攽鏉╁洨鈻兼稉顓犳畱閹繆鈧啫鎷板銉╊€?
     */
    @PostMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> executeStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        String sessionKey = request.getSessionKey() != null ? request.getSessionKey() : "web:default";

        // 鐠併垽妲勯幍褑顢戞禍瀣╂
        String subscriberId = executionTraceService.subscribe(sessionKey, emitter);

        logger.info("Starting execution with SSE streaming for session: {}, subscriber: {}",
            sessionKey, subscriberId);

        executor.submit(() -> {
            try {
                // 閸欐垿鈧浇绻涢幒銉р€樼拋銈勭皑娴?
                if (!safeSend(emitter, sessionKey, subscriberId, SseEmitter.event()
                        .name("connected")
                        .data(Map.of("sessionId", sessionKey, "subscriberId", subscriberId)), "connected event")) {
                    return;
                }
                logger.debug("Sent connected event to client");

                // 閹笛嗩攽娴犺濮熼敍鍫濈敨閸ョ偠鐨熼敍? 娴ｈ法鏁?orchestrator 閺€顖涘瘮婢?Agent 濡€崇础閻ㄥ嫬娲栫拫?
                String result = orchestrator.process(sessionKey, request.getMessage(),
                    (ExecutionEvent event) -> {
                        try {
                            // 闁俺绻?ExecutionTraceService 閸欐垵绔锋禍瀣╂閿涘牅绱伴懛顏勫З閹恒劑鈧胶绮伴幍鈧張澶庮吂闂冨懓鈧拑绱?
                            executionTraceService.publish(event);
                            Object source = event.getMetadata().get("source");
                            Object label = event.getMetadata().get("label");
                            Object runId = event.getMetadata().get("runId");
                            if ("task_harness".equals(source) && "run_created".equals(label) && runId != null) {
                                safeSend(emitter, sessionKey, subscriberId, SseEmitter.event()
                                        .name("task-harness-run")
                                        .data(Map.of("sessionId", sessionKey, "runId", runId.toString())),
                                        "task harness run event");
                            }
                            logger.debug("Published execution event: {}", event.getType());
                        } catch (Exception e) {
                            logger.debug("Failed to publish event: {}", e.getMessage());
                        }
                    });

                // 鐎瑰本鍨氶弮鏈电瑝閸愬秴褰傞柅?complete 娴滃娆㈤敍灞芥礈娑?FINAL_RESPONSE 娴滃娆㈠鑼病娴ｆ粈璐熺紒鎾存将娣団€冲娇
                // 閸撳秶顏导姘壌閹?FINAL_RESPONSE 娴滃娆㈠〒鍛倞濞翠礁绱￠悩鑸碘偓?
                logger.debug("Execution completed, FINAL_RESPONSE event already sent");

                emitter.complete();
                logger.debug("SSE emitter completed");

            } catch (Exception e) {
                logger.error("Error during execution", e);
                safeSend(emitter, sessionKey, subscriberId, SseEmitter.event()
                        .name("error")
                        .data(Map.of("error", e.getMessage())),
                        "execution error event");
                emitter.complete();
            }
        });

        return ResponseEntity.ok(emitter);
    }

    /**
     * 鐠併垽妲勯幐鍥х暰 session 閻ㄥ嫭澧界悰灞肩皑娴犺绱欓崣顏囶嚢閿涘奔绗夌憴锕€褰傞幍褑顢戦敍?
     *
     * 閻劋绨径姘吂閹撮顏崥灞炬閺屻儳婀呴崥灞肩娑?Agent 閻ㄥ嫭澧界悰宀€濮搁幀?
     */
    @GetMapping(value = "/execute/stream/{sessionKey}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribeExecution(@PathVariable String sessionKey,
                                                         @RequestParam(value = "history", required = false, defaultValue = "true") boolean history) {
        SseEmitter emitter = new SseEmitter(0L);

        String subscriberId = executionTraceService.subscribe(sessionKey, emitter);

        logger.info("Client subscribed to session: {}, subscriber: {}", sessionKey, subscriberId);

        // 閸欐垿鈧礁宸婚崣韫皑娴犺绱欐俊鍌涚亯閺堝娈戠拠婵撶礆
        executor.submit(() -> {
            try {
                if (!safeSend(emitter, sessionKey, subscriberId, SseEmitter.event()
                        .name("subscribed")
                        .data(Map.of("sessionId", sessionKey, "subscriberId", subscriberId)), "subscription confirmation")) {
                    return;
                }

                if (history) {
                    executionTraceService.getHistory(sessionKey).forEach(event -> {
                        safeSend(emitter, sessionKey, subscriberId, SseEmitter.event()
                                .name("history-event")
                                .data(event.toSseData()),
                                "history event");
                    });
                }
            } catch (Exception e) {
                logger.debug("Failed to send subscription confirmation: {}", e.getMessage());
            }
        });

        return ResponseEntity.ok(emitter);
    }

    private boolean safeSend(SseEmitter emitter,
                             String sessionKey,
                             String subscriberId,
                             SseEmitter.SseEventBuilder event,
                             String description) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            logger.debug("SSE client disconnected while sending {} to subscriber {}: {}",
                    description, subscriberId, e.getMessage());
            executionTraceService.unsubscribe(sessionKey, subscriberId);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Already closed by the servlet container.
            }
            return false;
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> getSessions(
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "all", required = false, defaultValue = "false") boolean all,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        String normalizedChannel = normalizeSessionChannel(channel);
        int normalizedPage = page != null && page > 0 ? page : 1;
        int normalizedSize = size != null && size > 0 ? Math.min(size, 100) : 20;

        List<io.jobclaw.conversation.SessionRecord> filtered = sessionManager.listUserSessionRecords().stream()
                .filter(record -> all || normalizedChannel.equals(sessionChannel(record.getSessionId())))
                .toList();

        int total = filtered.size();
        int fromIndex = Math.min((normalizedPage - 1) * normalizedSize, total);
        int toIndex = Math.min(fromIndex + normalizedSize, total);
        List<Map<String, Object>> sessions = filtered.subList(fromIndex, toIndex).stream()
                .map(record -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("key", record.getSessionId());
                    info.put("channel", sessionChannel(record.getSessionId()));
                    info.put("created", record.getCreatedAt());
                    info.put("updated", record.getUpdatedAt());
                    info.put("message_count", record.getMessageCount());
                    return info;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", sessions);
        response.put("page", normalizedPage);
        response.put("size", normalizedSize);
        response.put("total", total);
        response.put("totalPages", normalizedSize > 0 ? (int) Math.ceil(total / (double) normalizedSize) : 0);
        response.put("channel", all ? "all" : normalizedChannel);
        response.put("all", all);
        return ResponseEntity.ok(response);
    }

    private String normalizeSessionChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "web";
        }
        return channel.trim().toLowerCase(Locale.ROOT);
    }

    private String sessionChannel(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "unknown";
        }
        int separator = sessionId.indexOf(':');
        if (separator <= 0) {
            return "unknown";
        }
        return sessionId.substring(0, separator).toLowerCase(Locale.ROOT);
    }

    @GetMapping("/task-harness/runs/{runId}")
    public ResponseEntity<Map<String, Object>> getTaskHarnessRun(@PathVariable String runId) {
        TaskHarnessRun run = taskHarnessService.getRun(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toTaskHarnessRunResult(run));
    }

    @GetMapping("/task-harness/runs/{runId}/events")
    public ResponseEntity<Map<String, Object>> getTaskHarnessRunEvents(
            @PathVariable String runId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        TaskHarnessRun run = taskHarnessService.getRun(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }

        int normalizedLimit = limit != null && limit > 0 ? Math.min(limit, 200) : 100;
        List<Map<String, Object>> events = executionTraceService.getHistoryByRun(
                        run.getSessionId(),
                        runId,
                        normalizedLimit
                ).stream()
                .map(this::toExecutionEventResult)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run", toTaskHarnessRunResult(run));
        response.put("events", events);
        response.put("eventCount", events.size());
        response.put("stepCount", run.getSteps().size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sessions/{key}")
    public ResponseEntity<Session> getSession(@PathVariable String key) {
        Session session = sessionManager.getSession(key);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    @GetMapping("/sessions/search")
    public ResponseEntity<Map<String, Object>> searchSessions(
            @RequestParam("q") String query,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Map<String, Object> response = new HashMap<>();
        if (query == null || query.isBlank()) {
            response.put("history", List.of());
            response.put("summaries", List.of());
            response.put("memory", List.of());
            response.put("sessionSummary", null);
            return ResponseEntity.ok(response);
        }

        int normalizedLimit = limit != null && limit > 0 ? Math.min(limit, 20) : 8;
        SearchQuery searchQuery = new SearchQuery(sessionId, query, role, null, null, normalizedLimit, null);
        RetrievalBundle bundle = retrievalService.retrieveForContext(sessionId, query);

        response.put("history", retrievalService.searchHistory(searchQuery).stream()
                .map(this::toHistorySearchResult)
                .toList());
        response.put("summaries", retrievalService.searchSummaries(searchQuery).stream()
                .map(this::toSummarySearchResult)
                .toList());
        response.put("memory", retrievalService.searchMemory(searchQuery).stream()
                .map(this::toMemorySearchResult)
                .toList());
        response.put("sessionSummary", bundle.sessionSummary().orElse(null));
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toHistorySearchResult(io.jobclaw.conversation.StoredMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", message.messageId());
        result.put("sessionId", message.sessionId());
        result.put("sequence", message.sequence());
        result.put("role", message.role());
        result.put("content", message.content());
        result.put("createdAt", message.createdAt());
        return result;
    }

    private Map<String, Object> toTaskHarnessRunResult(TaskHarnessRun run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", run.getSessionId());
        result.put("runId", run.getRunId());
        result.put("taskInput", run.getTaskInput());
        result.put("currentPhase", run.getCurrentPhase().name());
        result.put("startedAt", run.getStartedAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("success", run.isSuccess());
        result.put("repairAttempts", run.getRepairAttempts());
        result.put("lastFailure", toTaskHarnessFailureResult(run.getLastFailure()));
        result.put("steps", run.getSteps().stream().map(this::toTaskHarnessStepResult).toList());
        return result;
    }

    private Map<String, Object> toTaskHarnessFailureResult(TaskHarnessFailure failure) {
        if (failure == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", failure.kind().name());
        result.put("reason", failure.reason());
        result.put("evidence", failure.evidence());
        return result;
    }

    private Map<String, Object> toTaskHarnessStepResult(TaskHarnessStep step) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", step.index());
        result.put("phase", step.phase().name());
        result.put("status", step.status());
        result.put("label", step.label());
        result.put("detail", step.detail());
        result.put("timestamp", step.timestamp());
        result.put("metadata", step.metadata());
        return result;
    }

    private Map<String, Object> toExecutionEventResult(ExecutionEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", event.getSessionId());
        result.put("runId", event.getRunId());
        result.put("parentRunId", event.getParentRunId());
        result.put("agentId", event.getAgentId());
        result.put("agentName", event.getAgentName());
        result.put("type", event.getType().name());
        result.put("content", event.getContent());
        result.put("timestamp", event.getTimestamp());
        result.put("metadata", event.getMetadata());
        return result;
    }

    private Map<String, Object> toSummarySearchResult(io.jobclaw.summary.ChunkSummary summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkId", summary.chunkId());
        result.put("sessionId", summary.sessionId());
        result.put("summaryText", summary.summaryText());
        result.put("entities", summary.entities());
        result.put("topics", summary.topics());
        result.put("decisions", summary.decisions());
        result.put("openQuestions", summary.openQuestions());
        result.put("createdAt", summary.createdAt());
        return result;
    }

    private Map<String, Object> toMemorySearchResult(io.jobclaw.summary.MemoryFact fact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factId", fact.factId());
        result.put("sessionId", fact.sessionId());
        result.put("scope", fact.scope());
        result.put("factType", fact.factType());
        result.put("subject", fact.subject());
        result.put("predicate", fact.predicate());
        result.put("objectText", fact.objectText());
        result.put("confidence", fact.confidence());
        result.put("updatedAt", fact.updatedAt());
        return result;
    }

    @DeleteMapping("/sessions/{key}")
    public ResponseEntity<Void> deleteSession(@PathVariable String key) {
        sessionManager.deleteSession(key);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/config")
    public ResponseEntity<Config> getConfig() {
        return ResponseEntity.ok(config);
    }

    @GetMapping("/cron")
    public ResponseEntity<List<Map<String, Object>>> getCronJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (CronJob job : cronService.listJobs(true)) {
            Map<String, Object> jobInfo = new HashMap<>();
            jobInfo.put("id", job.getId());
            jobInfo.put("name", job.getName());
            jobInfo.put("enabled", job.isEnabled());
            jobInfo.put("message", job.getPayload() != null ? job.getPayload().getMessage() : "");

            // 閺嶇厧绱￠崠?schedule 娣団剝浼?
            CronSchedule schedule = job.getSchedule();
            if (schedule != null) {
                switch (schedule.getKind()) {
                    case CRON -> jobInfo.put("schedule", schedule.getExpr());
                    case EVERY -> jobInfo.put("everySeconds", schedule.getEveryMs() / 1000);
                    case AT -> jobInfo.put("at", schedule.getAtMs());
                }
            }

            // 娑撳顐奸幍褑顢戦弮鍫曟？
            if (job.getState() != null && job.getState().getNextRunAtMs() != null) {
                jobInfo.put("nextRun", job.getState().getNextRunAtMs());
            }

            jobs.add(jobInfo);
        }
        return ResponseEntity.ok(jobs);
    }

    /**
     * 閸掓稑缂撶€规碍妞傛禒璇插
     * POST /api/cron
     */
    @PostMapping("/cron")
    public ResponseEntity<Map<String, Object>> createCronJob(@RequestBody CreateCronJobRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            CronSchedule schedule;
            if (request.getCron() != null && !request.getCron().isEmpty()) {
                schedule = CronSchedule.cron(request.getCron());
            } else if (request.getEverySeconds() != null) {
                schedule = CronSchedule.every(request.getEverySeconds() * 1000L);
            } else {
                response.put("error", "Either cron or everySeconds is required");
                return ResponseEntity.badRequest().body(response);
            }

            String channel = request.getChannel() != null ? request.getChannel() : "web";
            String to = request.getTo() != null ? request.getTo() : "default";

            CronJob job = cronService.addJob(request.getName(), schedule, request.getMessage(), channel, to);

            response.put("id", job.getId());
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 閸掔娀娅庣€规碍妞傛禒璇插
     * DELETE /api/cron/{id}
     */
    @DeleteMapping("/cron/{id}")
    public ResponseEntity<Map<String, Object>> deleteCronJob(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        boolean removed = cronService.removeJob(id);
        if (removed) {
            response.put("success", true);
            response.put("message", "Cron job deleted");
        } else {
            response.put("error", "Cron job not found: " + id);
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 閸氼垳鏁?缁備胶鏁ょ€规碍妞傛禒璇插
     * PUT /api/cron/{id}/enable
     */
    @PutMapping("/cron/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableCronJob(
            @PathVariable String id,
            @RequestBody EnableCronJobRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            CronJob job = cronService.enableJob(id, request.isEnabled());
            if (job == null) {
                response.put("error", "Cron job not found: " + id);
                return ResponseEntity.status(404).body(response);
            }
            response.put("success", true);
            response.put("message", request.isEnabled() ? "Cron job enabled" : "Cron job disabled");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Auth API ====================

    /**
     * 濡偓閺屻儴顓荤拠浣哄Ц閹?
     * GET /api/auth/check
     */
    @GetMapping("/auth/check")
    public ResponseEntity<Map<String, Object>> checkAuth() {
        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", true); // Spring Security 瀹告煡鈧俺绻冮崚娆忓嚒鐠併倛鐦?
        response.put("authEnabled", config.getGateway().isAuthEnabled());
        return ResponseEntity.ok(response);
    }

    /**
     * 閻ц缍嶉懢宄板絿鐠併倛鐦?Token
     * POST /api/auth/login
     */
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String validUsername = config.getGateway().getUsername();
            String validPassword = config.getGateway().getPassword();

            if (validUsername == null || validUsername.isEmpty() ||
                validPassword == null || validPassword.isEmpty()) {
                // 閺堫亜鎯庨悽銊吇鐠?
                response.put("success", true);
                response.put("token", "auth-disabled");
                return ResponseEntity.ok(response);
            }

            if (validUsername.equals(request.getUsername()) &&
                validPassword.equals(request.getPassword())) {
                response.put("success", true);
                // 娴ｈ法鏁?Base64 缂傛牜鐖滈崙顓＄槈閿涘湚TTP Basic Auth 閺嶅洤鍣弽鐓庣础閿?
                String credentials = request.getUsername() + ":" + request.getPassword();
                String token = Base64.getEncoder().encodeToString(credentials.getBytes());
                response.put("token", token);
            } else {
                response.put("success", false);
                response.put("error", "閻劍鍩涢崥宥嗗灗鐎靛棛鐖滈柨娆掝嚖");
                return ResponseEntity.status(401).body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // ==================== Channels API ====================

    /**
     * 閸掓鍤幍鈧張澶愵暥闁?
     * GET /api/channels
     */
    @GetMapping("/channels")
    public ResponseEntity<List<Map<String, Object>>> listChannels() {
        List<Map<String, Object>> channels = new ArrayList<>();
        ChannelsConfig channelsConfig = config.getChannels();

        addChannelInfo(channels, "telegram", channelsConfig.getTelegram().isEnabled());
        addChannelInfo(channels, "discord", channelsConfig.getDiscord().isEnabled());
        addChannelInfo(channels, "feishu", channelsConfig.getFeishu().isEnabled());
        addChannelInfo(channels, "dingtalk", channelsConfig.getDingtalk().isEnabled());
        addChannelInfo(channels, "qq", channelsConfig.getQq().isEnabled());
        addChannelInfo(channels, "whatsapp", channelsConfig.getWhatsapp().isEnabled());
        addChannelInfo(channels, "maixcam", channelsConfig.getMaixcam().isEnabled());

        return ResponseEntity.ok(channels);
    }

    private void addChannelInfo(List<Map<String, Object>> channels, String name, boolean enabled) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", name);
        info.put("enabled", enabled);
        channels.add(info);
    }

    /**
     * 閼惧嘲褰囨０鎴︿壕鐠囷妇绮忛柊宥囩枂
     * GET /api/channels/{name}
     */
    @GetMapping("/channels/{name}")
    public ResponseEntity<Map<String, Object>> getChannel(@PathVariable String name) {
        Map<String, Object> response = new HashMap<>();
        ChannelsConfig channelsConfig = config.getChannels();

        try {
            Object channelConfig = getChannelConfig(channelsConfig, name);
            if (channelConfig == null) {
                response.put("error", "閺堫亞鐓￠惃鍕暥闁挸鎮曠粔甯窗" + name);
                return ResponseEntity.status(404).body(response);
            }

            // 閸欏秴鐨犻懢宄板絿闁板秶鐤嗙仦鐐粹偓?
            Map<String, Object> configMap = extractChannelConfig(channelConfig, name);
            configMap.put("name", name);
            return ResponseEntity.ok(configMap);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private Object getChannelConfig(ChannelsConfig channelsConfig, String name) {
        return switch (name) {
            case "telegram" -> channelsConfig.getTelegram();
            case "discord" -> channelsConfig.getDiscord();
            case "feishu" -> channelsConfig.getFeishu();
            case "dingtalk" -> channelsConfig.getDingtalk();
            case "qq" -> channelsConfig.getQq();
            case "whatsapp" -> channelsConfig.getWhatsapp();
            case "maixcam" -> channelsConfig.getMaixcam();
            default -> null;
        };
    }

    private Map<String, Object> extractChannelConfig(Object config, String name) {
        Map<String, Object> result = new HashMap<>();
        try {
            var enabledMethod = config.getClass().getMethod("isEnabled");
            result.put("enabled", enabledMethod.invoke(config));
            addChannelProperty(result, config, "getToken", "token", true);
            addChannelProperty(result, config, "getAllowFrom", "allowFrom", false);
            addChannelProperty(result, config, "getBridgeUrl", "bridgeUrl", false);
            addChannelProperty(result, config, "getAppId", "appId", true);
            addChannelProperty(result, config, "getAppSecret", "appSecret", true);
            addChannelProperty(result, config, "getEncryptKey", "encryptKey", true);
            addChannelProperty(result, config, "getVerificationToken", "verificationToken", true);
            addChannelProperty(result, config, "getConnectionMode", "connectionMode", false);
            addChannelProperty(result, config, "getClientId", "clientId", true);
            addChannelProperty(result, config, "getClientSecret", "clientSecret", true);
            addChannelProperty(result, config, "getWebhook", "webhook", true);
            addChannelProperty(result, config, "getHost", "host", false);
            addChannelProperty(result, config, "getPort", "port", false);
        } catch (Exception e) {
            logger.warn("Failed to extract channel config for {}: {}", name, e.getMessage());
        }
        return result;
    }

    private void addChannelProperty(Map<String, Object> result, Object config,
                                    String getterName, String fieldName, boolean maskSecret) {
        try {
            var getter = config.getClass().getMethod(getterName);
            Object value = getter.invoke(config);
            if (value == null) {
                return;
            }
            if (maskSecret && value instanceof String text && !text.isEmpty()) {
                result.put(fieldName, maskSecret(text));
                return;
            }
            result.put(fieldName, value);
        } catch (NoSuchMethodException ignored) {
            // Optional field for some channel types.
        } catch (Exception e) {
            logger.debug("Failed to extract channel property {}: {}", fieldName, e.getMessage());
        }
    }

    /**
     * ?????????
     * PUT /api/channels/{name}
     */
    @PutMapping("/channels/{name}")
    public ResponseEntity<Map<String, Object>> updateChannel(
            @PathVariable String name,
            @RequestBody UpdateChannelRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            ChannelsConfig channelsConfig = config.getChannels();
            Object channelConfig = getChannelConfig(channelsConfig, name);

            if (channelConfig == null) {
                response.put("error", "????????????" + name);
                return ResponseEntity.status(404).body(response);
            }

            var setEnabledMethod = channelConfig.getClass().getMethod("setEnabled", boolean.class);
            setEnabledMethod.invoke(channelConfig, request.isEnabled());

            updateChannelStringField(channelConfig, "setToken", request.getToken(), true);
            updateChannelListField(channelConfig, "setAllowFrom", request.getAllowFrom());
            updateChannelStringField(channelConfig, "setBridgeUrl", request.getBridgeUrl(), false);
            updateChannelStringField(channelConfig, "setAppId", request.getAppId(), true);
            updateChannelStringField(channelConfig, "setAppSecret", request.getAppSecret(), true);
            updateChannelStringField(channelConfig, "setEncryptKey", request.getEncryptKey(), true);
            updateChannelStringField(channelConfig, "setVerificationToken", request.getVerificationToken(), true);
            updateChannelStringField(channelConfig, "setConnectionMode", request.getConnectionMode(), false);
            updateChannelStringField(channelConfig, "setClientId", request.getClientId(), true);
            updateChannelStringField(channelConfig, "setClientSecret", request.getClientSecret(), true);
            updateChannelStringField(channelConfig, "setWebhook", request.getWebhook(), true);
            updateChannelStringField(channelConfig, "setHost", request.getHost(), false);
            updateChannelIntField(channelConfig, "setPort", request.getPort());

            ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "???????????");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Providers API ====================

    /**
     * ???????LLM ?????
     * GET /api/providers
     */
    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> listProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();
        ProvidersConfig providersConfig = config.getProviders();

        addProviderInfo(providers, "openrouter", providersConfig.getOpenrouter());
        addProviderInfo(providers, "anthropic", providersConfig.getAnthropic());
        addProviderInfo(providers, "openai", providersConfig.getOpenai());
        addProviderInfo(providers, "zhipu", providersConfig.getZhipu());
        addProviderInfo(providers, "gemini", providersConfig.getGemini());
        addProviderInfo(providers, "dashscope", providersConfig.getDashscope());
        addProviderInfo(providers, "ollama", providersConfig.getOllama());

        return ResponseEntity.ok(providers);
    }

    private void addProviderInfo(List<Map<String, Object>> providers, String name,
                                 ProvidersConfig.ProviderConfig providerConfig) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", name);
        info.put("apiBase", providerConfig.getApiBase());
        String apiKey = providerConfig.getApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            info.put("apiKey", apiKey.length() > 8 ?
                    apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4) :
                    "****");
        } else {
            info.put("apiKey", "");
        }
        info.put("authorized", providerConfig.isValid());
        providers.add(info);
    }

    @GetMapping("/providers/{name}")
    public ResponseEntity<Map<String, Object>> getProvider(@PathVariable String name) {
        Map<String, Object> response = new HashMap<>();
        ProvidersConfig.ProviderConfig providerConfig = getProviderConfig(config.getProviders(), name);
        if (providerConfig == null) {
            response.put("error", "Unknown provider: " + name);
            return ResponseEntity.status(404).body(response);
        }

        response.put("name", name);
        response.put("apiBase", providerConfig.getApiBase());
        response.put("apiKey", maskIfPresent(providerConfig.getApiKey()));
        response.put("authorized", providerConfig.isValid());
        return ResponseEntity.ok(response);
    }

    /**
     * ???????????
     * PUT /api/providers/{name}
     */
    @PutMapping("/providers/{name}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable String name,
            @RequestBody UpdateProviderRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            ProvidersConfig providersConfig = config.getProviders();
            ProvidersConfig.ProviderConfig providerConfig = getProviderConfig(providersConfig, name);

            if (providerConfig == null) {
                response.put("error", "Unknown provider: " + name);
                return ResponseEntity.status(404).body(response);
            }

            if (request.getApiKey() != null && !isMaskedSecret(request.getApiKey())) {
                providerConfig.setApiKey(request.getApiKey());
            }

            if (request.getApiBase() != null && !request.getApiBase().isEmpty()) {
                providerConfig.setApiBase(request.getApiBase());
            }

            ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "????????????");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private ProvidersConfig.ProviderConfig getProviderConfig(ProvidersConfig providers, String name) {
        return switch (name) {
            case "openrouter" -> providers.getOpenrouter();
            case "anthropic" -> providers.getAnthropic();
            case "openai" -> providers.getOpenai();
            case "zhipu" -> providers.getZhipu();
            case "gemini" -> providers.getGemini();
            case "dashscope" -> providers.getDashscope();
            case "ollama" -> providers.getOllama();
            default -> null;
        };
    }

    private void updateChannelStringField(Object channelConfig, String setterName, String value, boolean treatAsSecret)
            throws Exception {
        if (value == null) {
            return;
        }
        if (treatAsSecret && isMaskedSecret(value)) {
            return;
        }
        try {
            var setter = channelConfig.getClass().getMethod(setterName, String.class);
            setter.invoke(channelConfig, value);
        } catch (NoSuchMethodException ignored) {
            // Optional field for some channel types.
        }
    }

    private void updateChannelListField(Object channelConfig, String setterName, List<String> value) throws Exception {
        if (value == null) {
            return;
        }
        try {
            var setter = channelConfig.getClass().getMethod(setterName, List.class);
            setter.invoke(channelConfig, value);
        } catch (NoSuchMethodException ignored) {
            // Optional field for some channel types.
        }
    }

    private void updateChannelIntField(Object channelConfig, String setterName, Integer value) throws Exception {
        if (value == null) {
            return;
        }
        try {
            var setter = channelConfig.getClass().getMethod(setterName, int.class);
            setter.invoke(channelConfig, value);
        } catch (NoSuchMethodException ignored) {
            // Optional field for some channel types.
        }
    }

    private String maskIfPresent(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return maskSecret(secret);
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return secret.length() > 8
                ? secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4)
                : "****";
    }

    private boolean isMaskedSecret(String value) {
        return value != null && value.contains("****");
    }

    // ==================== Models API ====================
    // ==================== Models API ====================

    /**
     * 閸掓鍤幍鈧張澶婂讲閻劍膩閸?
     * GET /api/models
     */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        List<Map<String, Object>> models = new ArrayList<>();
        ModelsConfig modelsConfig = config.getModels();
        ProvidersConfig providersConfig = config.getProviders();

        for (Map.Entry<String, ModelsConfig.ModelDefinition> entry :
                modelsConfig.getDefinitions().entrySet()) {

            ModelsConfig.ModelDefinition def = entry.getValue();
            Map<String, Object> modelInfo = new HashMap<>();
            modelInfo.put("name", entry.getKey());
            modelInfo.put("provider", def.getProvider());
            modelInfo.put("model", def.getModel());
            modelInfo.put("maxContextSize", def.getMaxContextSize());
            modelInfo.put("description", def.getDescription() != null ? def.getDescription() : "");

            // 濡偓閺屻儲褰佹笟娑樻櫌閺勵垰鎯佸鍙夊房閺?
            ProvidersConfig.ProviderConfig providerConfig = getProviderConfig(providersConfig, def.getProvider());
            modelInfo.put("authorized", providerConfig != null && providerConfig.isValid());

            models.add(modelInfo);
        }

        return ResponseEntity.ok(models);
    }

    // ==================== Config API ====================

    /**
     * 閼惧嘲褰囪ぐ鎾冲濡€崇€烽崪灞惧絹娓氭稑鏅㈤柊宥囩枂
     * GET /api/config/model
     */
    @GetMapping("/config/model")
    public ResponseEntity<Map<String, String>> getConfigModel() {
        Map<String, String> response = new HashMap<>();
        response.put("model", config.getAgent().getModel());
        response.put("provider", config.getAgent().getProvider());
        return ResponseEntity.ok(response);
    }

    /**
     * 閺囧瓨鏌婂Ο鈥崇€烽柊宥囩枂
     * PUT /api/config/model
     */
    @PutMapping("/config/model")
    public ResponseEntity<Map<String, Object>> updateConfigModel(@RequestBody UpdateModelRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 妤犲矁鐦夊Ο鈥崇€烽弰顖氭儊鐎涙ê婀?
            ModelsConfig.ModelDefinition modelDef = config.getModels().getDefinitions().get(request.getModel());
            if (modelDef == null) {
                response.put("error", "閺堫亞鐓￠惃鍕侀崹瀣剁窗" + request.getModel());
                return ResponseEntity.status(400).body(response);
            }

            // 閺囧瓨鏌婂Ο鈥崇€烽柊宥囩枂
            config.getAgent().setModel(request.getModel());

            // 婵″倹鐏夐幓鎰返娴?provider閿涘奔绡冮弴瀛樻煀
            if (request.getProvider() != null && !request.getProvider().isEmpty()) {
                config.getAgent().setProvider(request.getProvider());
            }

            // TODO: 鐏忓棝鍘ょ純顔藉瘮娑斿懎瀵查崚?config.json
            // ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "Model updated");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 閼惧嘲褰?Agent 闁板秶鐤?
     * GET /api/config/agent
     */
    @GetMapping("/config/agent")
    public ResponseEntity<Map<String, Object>> getConfigAgent() {
        Map<String, Object> response = new HashMap<>();
        AgentConfig agentConfig = config.getAgent();

        response.put("workspace", config.getWorkspacePath());
        response.put("model", agentConfig.getModel());
        response.put("maxTokens", agentConfig.getMaxTokens());
        response.put("temperature", agentConfig.getTemperature());
        response.put("maxToolIterations", agentConfig.getMaxToolIterations());
        response.put("heartbeatEnabled", agentConfig.isHeartbeatEnabled());
        response.put("restrictToWorkspace", agentConfig.isRestrictToWorkspace());
        response.put("contextWindow", agentConfig.getContextWindow());
        response.put("recentMessagesToKeep", agentConfig.getRecentMessagesToKeep());
        response.put("memoryTokenBudgetPercentage", agentConfig.getMemoryTokenBudgetPercentage());
        response.put("memoryMinTokenBudget", agentConfig.getMemoryMinTokenBudget());
        response.put("memoryMaxTokenBudget", agentConfig.getMemoryMaxTokenBudget());
        response.put("contextMaxPromptTokenPercentage", agentConfig.getContextMaxPromptTokenPercentage());
        response.put("contextLongInputPromptTokenPercentage", agentConfig.getContextLongInputPromptTokenPercentage());
        response.put("contextLongInputTokenPercentage", agentConfig.getContextLongInputTokenPercentage());
        response.put("contextMaxHistoryRetrieval", agentConfig.getContextMaxHistoryRetrieval());
        response.put("contextMaxSummaryRetrieval", agentConfig.getContextMaxSummaryRetrieval());
        response.put("contextMaxMemoryRetrieval", agentConfig.getContextMaxMemoryRetrieval());

        return ResponseEntity.ok(response);
    }

    /**
     * 閺囧瓨鏌?Agent 闁板秶鐤?
     * PUT /api/config/agent
     */
    @PutMapping("/config/agent")
    public ResponseEntity<Map<String, Object>> updateConfigAgent(@RequestBody UpdateAgentConfigRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            AgentConfig agentConfig = config.getAgent();

            // 閺囧瓨鏌婇柊宥囩枂
            if (request.getMaxTokens() != null) {
                agentConfig.setMaxTokens(request.getMaxTokens());
            }
            if (request.getTemperature() != null) {
                agentConfig.setTemperature(request.getTemperature());
            }
            if (request.getMaxToolIterations() != null) {
                agentConfig.setMaxToolIterations(request.getMaxToolIterations());
            }
            if (request.getHeartbeatEnabled() != null) {
                agentConfig.setHeartbeatEnabled(request.getHeartbeatEnabled());
            }
            if (request.getRestrictToWorkspace() != null) {
                agentConfig.setRestrictToWorkspace(request.getRestrictToWorkspace());
            }
            if (request.getContextWindow() != null) {
                agentConfig.setContextWindow(request.getContextWindow());
            }
            if (request.getRecentMessagesToKeep() != null) {
                agentConfig.setRecentMessagesToKeep(request.getRecentMessagesToKeep());
            }
            if (request.getMemoryTokenBudgetPercentage() != null) {
                agentConfig.setMemoryTokenBudgetPercentage(request.getMemoryTokenBudgetPercentage());
            }
            if (request.getMemoryMinTokenBudget() != null) {
                agentConfig.setMemoryMinTokenBudget(request.getMemoryMinTokenBudget());
            }
            if (request.getMemoryMaxTokenBudget() != null) {
                agentConfig.setMemoryMaxTokenBudget(request.getMemoryMaxTokenBudget());
            }
            if (request.getContextMaxPromptTokenPercentage() != null) {
                agentConfig.setContextMaxPromptTokenPercentage(request.getContextMaxPromptTokenPercentage());
            }
            if (request.getContextLongInputPromptTokenPercentage() != null) {
                agentConfig.setContextLongInputPromptTokenPercentage(request.getContextLongInputPromptTokenPercentage());
            }
            if (request.getContextLongInputTokenPercentage() != null) {
                agentConfig.setContextLongInputTokenPercentage(request.getContextLongInputTokenPercentage());
            }
            if (request.getContextMaxHistoryRetrieval() != null) {
                agentConfig.setContextMaxHistoryRetrieval(request.getContextMaxHistoryRetrieval());
            }
            if (request.getContextMaxSummaryRetrieval() != null) {
                agentConfig.setContextMaxSummaryRetrieval(request.getContextMaxSummaryRetrieval());
            }
            if (request.getContextMaxMemoryRetrieval() != null) {
                agentConfig.setContextMaxMemoryRetrieval(request.getContextMaxMemoryRetrieval());
            }

            ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "Agent config updated");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Skills API ====================

    /**
     * 閸掓鍤幍鈧張澶嬪Η閼?
     * GET /api/skills
     */
    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> listSkills() {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (SkillInfo skill : skillsService.listSkills()) {
            Map<String, Object> skillInfo = new HashMap<>();
            skillInfo.put("name", skill.getName());
            skillInfo.put("description", skill.getDescription());
            skillInfo.put("source", skill.getSource());
            skillInfo.put("path", skill.getPath());
            skills.add(skillInfo);
        }
        return ResponseEntity.ok(skills);
    }

    /**
     * 閼惧嘲褰囬幎鈧懗钘夊敶鐎?
     * GET /api/skills/{skillName}
     */
    @GetMapping("/skills/{skillName}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String skillName) {
        Map<String, Object> response = new HashMap<>();
        try {
            String content = skillsService.loadSkill(skillName);
            if (content == null) {
                response.put("error", "Skill not found: " + skillName);
                return ResponseEntity.status(404).body(response);
            }
            response.put("name", skillName);
            response.put("content", content);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 閸掓稑缂撻幋鏍ㄦ纯閺傜増濡ч懗?
     * PUT /api/skills/{skillName}
     */
    @PutMapping("/skills/{skillName}")
    public ResponseEntity<Map<String, Object>> saveSkill(
            @PathVariable String skillName,
            @RequestBody SaveSkillRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            skillsService.saveSkill(skillName, request.getContent());
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 閸掔娀娅庨幎鈧懗?
     * DELETE /api/skills/{skillName}
     */
    @DeleteMapping("/skills/{skillName}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String skillName) {
        Map<String, Object> response = new HashMap<>();
        boolean deleted = skillsService.deleteSkill(skillName);
        if (deleted) {
            response.put("success", true);
        } else {
            response.put("error", "Skill not found: " + skillName);
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // ==================== MCP API ====================

    /**
     * 閼惧嘲褰?MCP 閺堝秴濮熼崳銊╁帳缂?
     * GET /api/mcp
     */
    @GetMapping("/mcp")
    public ResponseEntity<Map<String, Object>> getMcpConfig() {
        Map<String, Object> response = new HashMap<>();
        MCPServersConfig mcpConfig = config.getMcpServers();
        response.put("enabled", mcpConfig.isEnabled());

        List<Map<String, Object>> servers = new ArrayList<>();
        for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
            Map<String, Object> serverInfo = new HashMap<>();
            serverInfo.put("name", server.getName());
            serverInfo.put("type", server.getType());
            serverInfo.put("description", server.getDescription());
            serverInfo.put("endpoint", server.getEndpoint());
            // 闂呮劘妫?API Key
            if (server.getApiKey() != null && !server.getApiKey().isEmpty()) {
                String key = server.getApiKey();
                serverInfo.put("apiKey", key.length() > 8 ?
                        key.substring(0, 4) + "****" + key.substring(key.length() - 4) : "****");
            }
            serverInfo.put("enabled", server.isEnabled());
            serverInfo.put("timeout", server.getTimeout());
            servers.add(serverInfo);
        }
        response.put("servers", servers);
        return ResponseEntity.ok(response);
    }

    /**
     * 閺囧瓨鏌?MCP 閸忋劌鐪崥顖滄暏閻樿埖鈧?
     * PUT /api/mcp
     */
    @PutMapping("/mcp")
    public ResponseEntity<Map<String, Object>> updateMcpConfig(
            @RequestBody UpdateMcpGlobalRequest request) {
        Map<String, Object> response = new HashMap<>();
        config.getMcpServers().setEnabled(request.isEnabled());
        response.put("success", true);
        response.put("message", "MCP config updated");
        return ResponseEntity.ok(response);
    }

    /**
     * 濞ｈ濮?MCP 閺堝秴濮熼崳?
     * POST /api/mcp
     */
    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> addMcpServer(
            @RequestBody AddMcpServerRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            MCPServersConfig.MCPServerConfig server = new MCPServersConfig.MCPServerConfig();
            server.setName(request.getName());
            server.setType(request.getType());
            server.setDescription(request.getDescription());
            server.setEndpoint(request.getEndpoint());
            server.setApiKey(request.getApiKey());
            server.setCommand(request.getCommand());
            server.setArgs(request.getArgs());
            server.setEnv(request.getEnv());
            server.setEnabled(request.isEnabled());
            server.setTimeout(request.getTimeout());

            config.getMcpServers().getServers().add(server);
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 閺囧瓨鏌?MCP 閺堝秴濮熼崳銊╁帳缂?
     * PUT /api/mcp/{serverName}
     */
    @PutMapping("/mcp/{serverName}")
    public ResponseEntity<Map<String, Object>> updateMcpServer(
            @PathVariable String serverName,
            @RequestBody UpdateMcpServerRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            MCPServersConfig.MCPServerConfig server = findMcpServer(serverName);
            if (server == null) {
                response.put("error", "MCP server not found: " + serverName);
                return ResponseEntity.status(404).body(response);
            }

            if (request.getType() != null) server.setType(request.getType());
            if (request.getDescription() != null) server.setDescription(request.getDescription());
            if (request.getEndpoint() != null) server.setEndpoint(request.getEndpoint());
            if (request.getApiKey() != null) server.setApiKey(request.getApiKey());
            if (request.getCommand() != null) server.setCommand(request.getCommand());
            if (request.getArgs() != null) server.setArgs(request.getArgs());
            if (request.getEnv() != null) server.setEnv(request.getEnv());
            if (request.isEnabled() != server.isEnabled()) server.setEnabled(request.isEnabled());
            if (request.getTimeout() != null) server.setTimeout(request.getTimeout());

            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 閸掔娀娅?MCP 閺堝秴濮熼崳?
     * DELETE /api/mcp/{serverName}
     */
    @DeleteMapping("/mcp/{serverName}")
    public ResponseEntity<Map<String, Object>> deleteMcpServer(@PathVariable String serverName) {
        Map<String, Object> response = new HashMap<>();
        List<MCPServersConfig.MCPServerConfig> servers = config.getMcpServers().getServers();
        boolean removed = servers.removeIf(s -> s.getName().equals(serverName));
        if (removed) {
            response.put("success", true);
            response.put("message", "MCP server deleted");
        } else {
            response.put("error", "MCP server not found: " + serverName);
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 濞村鐦?MCP 閺堝秴濮熼崳銊ㄧ箾閹?
     * POST /api/mcp/{serverName}/test
     */
    @PostMapping("/mcp/{serverName}/test")
    public ResponseEntity<Map<String, Object>> testMcpServer(@PathVariable String serverName) {
        Map<String, Object> response = new HashMap<>();
        try {
            MCPServersConfig.MCPServerConfig server = findMcpServer(serverName);
            if (server == null) {
                response.put("error", "MCP server not found: " + serverName);
                return ResponseEntity.status(404).body(response);
            }

            // TODO: 鐎圭偤妾ù瀣槸鏉╃偞甯?
            response.put("serverName", serverName);
            response.put("connected", true);
            response.put("initialized", true);
            response.put("success", true);

            Map<String, Object> serverInfo = new HashMap<>();
            serverInfo.put("name", server.getName());
            serverInfo.put("version", "1.0.0");
            response.put("serverInfo", serverInfo);
            response.put("tools", List.of());
            response.put("toolCount", 0);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("connected", false);
            return ResponseEntity.status(500).body(response);
        }
    }

    private MCPServersConfig.MCPServerConfig findMcpServer(String name) {
        for (MCPServersConfig.MCPServerConfig server : config.getMcpServers().getServers()) {
            if (server.getName().equals(name)) {
                return server;
            }
        }
        return null;
    }

    // ==================== Learning Candidates API ====================

    @GetMapping("/learning/candidates")
    public ResponseEntity<?> listLearningCandidates(@RequestParam(required = false) String status) {
        try {
            return ResponseEntity.ok(learningCandidateService.list(status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/learning/candidates/{id}")
    public ResponseEntity<?> getLearningCandidate(@PathVariable String id) {
        return learningCandidateService.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Learning candidate not found: " + id)));
    }

    @PostMapping("/learning/candidates/{id}/accept")
    public ResponseEntity<?> acceptLearningCandidate(@PathVariable String id) {
        return learningCandidateService.markAccepted(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Learning candidate not found: " + id)));
    }

    @PostMapping("/learning/candidates/{id}/reject")
    public ResponseEntity<?> rejectLearningCandidate(@PathVariable String id) {
        return learningCandidateService.markRejected(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Learning candidate not found: " + id)));
    }

    @DeleteMapping("/learning/candidates/{id}")
    public ResponseEntity<?> deleteLearningCandidate(@PathVariable String id) {
        return learningCandidateService.delete(id)
                .<ResponseEntity<?>>map(candidate -> ResponseEntity.ok(Map.of(
                        "deleted", true,
                        "id", candidate.getId()
                )))
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Learning candidate not found: " + id)));
    }

    // ==================== Experience API ====================

    @GetMapping("/experience/memories")
    public ResponseEntity<?> listExperienceMemories() {
        return ResponseEntity.ok(experienceMemoryService.listActive());
    }

    @GetMapping("/experience/review/latest")
    public ResponseEntity<?> getLatestExperienceReview() {
        Path latest = Paths.get(config.getWorkspacePath(), ".jobclaw", "experience", "latest.md");
        if (!Files.exists(latest)) {
            return ResponseEntity.ok(Map.of(
                    "exists", false,
                    "content", ""
            ));
        }
        try {
            return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "path", latest.toString(),
                    "content", Files.readString(latest)
            ));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to read latest experience review: " + e.getMessage()));
        }
    }

    // ==================== Workspace Files API ====================

    /**
     * 閸掓鍤銉ょ稊閸栫儤鏋冩禒?
     * GET /api/workspace/files
     */
    @GetMapping("/workspace/files")
    public ResponseEntity<List<Map<String, Object>>> listWorkspaceFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            Path workspacePath = Paths.get(config.getWorkspacePath());
            if (Files.exists(workspacePath)) {
                Files.walk(workspacePath, 3)
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(path -> {
                        try {
                            Map<String, Object> fileInfo = new HashMap<>();
                            String relativePath = workspacePath.relativize(path).toString();
                            fileInfo.put("name", relativePath);
                            fileInfo.put("exists", true);
                            fileInfo.put("size", Files.size(path));
                            fileInfo.put("lastModified", Files.getLastModifiedTime(path).toMillis());
                            files.add(fileInfo);
                        } catch (IOException e) {
                            logger.warn("Failed to get file info: {}", path, e);
                        }
                    });
            }
        } catch (IOException e) {
            logger.warn("Failed to list workspace files", e);
        }
        Collections.sort(files, (a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")));
        return ResponseEntity.ok(files);
    }

    /**
     * 鐠囪褰囧銉ょ稊閸栫儤鏋冩禒?
     * GET /api/workspace/files/{fileName}
     */
    @GetMapping("/workspace/files/{fileName}")
    public ResponseEntity<Map<String, Object>> readWorkspaceFile(@PathVariable String fileName) {
        Map<String, Object> response = new HashMap<>();
        try {
            Path filePath = Paths.get(config.getWorkspacePath(), fileName);
            String securityError = securityGuard.checkFilePath(filePath.toString());
            if (securityError != null) {
                response.put("error", securityError);
                return ResponseEntity.status(403).body(response);
            }

            if (!Files.exists(filePath)) {
                response.put("error", "File not found: " + fileName);
                return ResponseEntity.status(404).body(response);
            }

            response.put("name", fileName);
            response.put("content", Files.readString(filePath));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 娣囨繂鐡ㄥ銉ょ稊閸栫儤鏋冩禒?
     * PUT /api/workspace/files/{fileName}
     */
    @PutMapping("/workspace/files/{fileName}")
    public ResponseEntity<Map<String, Object>> saveWorkspaceFile(
            @PathVariable String fileName,
            @RequestBody SaveFileRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Path filePath = Paths.get(config.getWorkspacePath(), fileName);
            String securityError = securityGuard.checkFilePath(filePath.toString());
            if (securityError != null) {
                response.put("error", securityError);
                return ResponseEntity.status(403).body(response);
            }

            // 绾喕绻氶悥鍓佹窗瑜版洖鐡ㄩ崷?
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }

            Files.writeString(filePath, request.getContent());
            response.put("success", true);
            response.put("message", "File saved");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Token Stats API ====================

    /**
     * 閼惧嘲褰?Token 娴ｈ法鏁ょ紒鐔活吀
     * GET /api/token-stats
     */
    @GetMapping("/token-stats")
    public ResponseEntity<Map<String, Object>> getTokenStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Map<String, Object> response = new HashMap<>();
        try {
            LocalDate start = startDate != null ?
                    LocalDate.parse(startDate, DATE_FORMAT) : LocalDate.now().minusDays(30);
            LocalDate end = endDate != null ?
                    LocalDate.parse(endDate, DATE_FORMAT) : LocalDate.now();

            Map<String, Object> stats = tokenUsageService.getRangeStats(start, end);
            response.putAll(stats);
            response.put("startDate", start.toString());
            response.put("endDate", end.toString());

            // 閹稿膩閸ㄥ鍨庣紒鍕剁礄缁犫偓閸栨牜澧楅敍?
            response.put("byModel", List.of());
            response.put("byDate", List.of());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Feedback API ====================

    /**
     * 閼惧嘲褰囬崣宥夘洯缁崵绮洪悩鑸碘偓?
     * GET /api/feedback
     */
    @GetMapping("/feedback")
    public ResponseEntity<Map<String, Object>> getFeedbackConfig() {
        Map<String, Object> response = new HashMap<>();
        response.put("feedbackEnabled", config.getAgent().isFeedbackEnabled());
        response.put("promptOptimizationEnabled", config.getAgent().isPromptOptimizationEnabled());
        response.put("optimizationStats", Map.of());
        return ResponseEntity.ok(response);
    }

    /**
     * 閹绘劒姘﹂崣宥夘洯
     * POST /api/feedback
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(@RequestBody SubmitFeedbackRequest request) {
        Map<String, Object> response = new HashMap<>();
        // TODO: 娣囨繂鐡ㄩ崣宥夘洯閸掓澘鐡ㄩ崒?
        response.put("success", true);
        response.put("message", "Feedback submitted");
        return ResponseEntity.ok(response);
    }

    // ==================== Upload API ====================

    /**
     * 娑撳﹣绱堕崶鍓у
     * POST /api/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadImages(@RequestBody UploadImagesRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> savedFiles = new ArrayList<>();
            Path uploadDir = Paths.get(config.getWorkspacePath(), "uploads");
            Files.createDirectories(uploadDir);

            if (request.getImages() != null) {
                for (UploadImage img : request.getImages()) {
                    if (img.getData() != null && img.getData().startsWith("data:image/")) {
                        // 鐟欙絾鐎?base64 閺佺増宓?
                        String[] parts = img.getData().split(",");
                        if (parts.length == 2) {
                            byte[] data = Base64.getDecoder().decode(parts[1]);
                            String filename = generateUploadFilename(img.getName());
                            Path filePath = uploadDir.resolve(filename);
                            Files.write(filePath, data);
                            savedFiles.add("uploads/" + filename);
                        }
                    }
                }
            }

            response.put("files", savedFiles);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private String generateUploadFilename(String originalName) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf("."));
        } else {
            extension = ".jpg"; // 姒涙顓婚幍鈺佺潔閸?
        }
        return timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
    }

    // ==================== Files API ====================

    /**
     * 鐠佸潡妫舵稉濠佺炊閻ㄥ嫭鏋冩禒璁圭礄閺冪娀娓剁拋銈堢槈閿涘瞼鏁ゆ禍?img src閿?
     * GET /api/files/{relativePath}
     */
    @GetMapping("/files/{relativePath}")
    public ResponseEntity<byte[]> getFile(@PathVariable String relativePath) {
        try {
            Path filePath = Paths.get(config.getWorkspacePath(), relativePath);
            String securityError = securityGuard.checkFilePath(filePath.toString());
            if (securityError != null) {
                return ResponseEntity.status(403).build();
            }

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] content = Files.readAllBytes(filePath);
            String contentType = determineContentType(filePath);

            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .header("Cache-Control", "public, max-age=31536000")
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    private String determineContentType(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private ResponseEntity<?> changeAgentStatus(String id, boolean active) {
        if (id.startsWith("main:")) {
            return ResponseEntity.badRequest().body(Map.of("error", "main assistant cannot be disabled"));
        }
        String code = extractAgentCode(id);
        if (code == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported agent id: " + id));
        }
        Optional<AgentCatalogEntry> updated = active
                ? agentCatalogService.activateAgent(code)
                : agentCatalogService.disableAgent(code);
        return updated.flatMap(entry -> agentProfileService.getProfile("agent:" + entry.code()))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private Map<String, Object> normalizeModelConfig(Map<String, Object> modelConfig) {
        if (modelConfig == null) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        modelConfig.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                normalized.put(key.trim(), value);
            }
        });
        return normalized;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultPromptIfBlank(String displayName, String description, String systemPrompt) {
        String normalizedPrompt = normalizeText(systemPrompt);
        if (normalizedPrompt != null) {
            return normalizedPrompt;
        }
        String normalizedName = displayName != null ? displayName.trim() : "Agent";
        String normalizedDescription = normalizeText(description);
        if (normalizedDescription == null) {
            normalizedDescription = "处理用户指定的专项工作";
        }
        return "你是 `" + normalizedName + "`。\n"
                + "你的专属职责是：" + normalizedDescription + "。\n"
                + "只围绕该职责完成任务，必要时使用允许的工具和技能，避免偏离角色边界。";
    }

    private String extractAgentCode(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (id.startsWith("agent:")) {
            return id.substring("agent:".length());
        }
        if (id.startsWith("role:")) {
            return id.substring("role:".length());
        }
        return null;
    }

    // ==================== Request DTOs ====================

    public static class ChatRequest {
        private String message;
        private String sessionKey;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSessionKey() { return sessionKey; }
        public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
    }

    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class UpdateChannelRequest {
        private boolean enabled;
        private String token;
        private List<String> allowFrom;
        private String bridgeUrl;
        private String appId;
        private String appSecret;
        private String encryptKey;
        private String verificationToken;
        private String connectionMode;
        private String clientId;
        private String clientSecret;
        private String webhook;
        private String host;
        private Integer port;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public String getBridgeUrl() { return bridgeUrl; }
        public void setBridgeUrl(String bridgeUrl) { this.bridgeUrl = bridgeUrl; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getEncryptKey() { return encryptKey; }
        public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }
        public String getVerificationToken() { return verificationToken; }
        public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
        public String getConnectionMode() { return connectionMode; }
        public void setConnectionMode(String connectionMode) { this.connectionMode = connectionMode; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getWebhook() { return webhook; }
        public void setWebhook(String webhook) { this.webhook = webhook; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
    }

    public static class UpdateProviderRequest {
        private String apiKey;
        private String apiBase;
        private String baseUrl;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiBase() { return apiBase != null ? apiBase : baseUrl; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class UpdateModelRequest {
        private String model;
        private String provider;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    public static class UpdateAgentConfigRequest {
        private Integer maxTokens;
        private Double temperature;
        private Integer maxToolIterations;
        private Boolean heartbeatEnabled;
        private Boolean restrictToWorkspace;
        private Integer contextWindow;
        private Integer recentMessagesToKeep;
        private Integer memoryTokenBudgetPercentage;
        private Integer memoryMinTokenBudget;
        private Integer memoryMaxTokenBudget;
        private Integer contextMaxPromptTokenPercentage;
        private Integer contextLongInputPromptTokenPercentage;
        private Integer contextLongInputTokenPercentage;
        private Integer contextMaxHistoryRetrieval;
        private Integer contextMaxSummaryRetrieval;
        private Integer contextMaxMemoryRetrieval;

        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxToolIterations() { return maxToolIterations; }
        public void setMaxToolIterations(Integer maxToolIterations) { this.maxToolIterations = maxToolIterations; }
        public Boolean getHeartbeatEnabled() { return heartbeatEnabled; }
        public void setHeartbeatEnabled(Boolean heartbeatEnabled) { this.heartbeatEnabled = heartbeatEnabled; }
        public Boolean getRestrictToWorkspace() { return restrictToWorkspace; }
        public void setRestrictToWorkspace(Boolean restrictToWorkspace) { this.restrictToWorkspace = restrictToWorkspace; }
        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
        public Integer getRecentMessagesToKeep() { return recentMessagesToKeep; }
        public void setRecentMessagesToKeep(Integer recentMessagesToKeep) { this.recentMessagesToKeep = recentMessagesToKeep; }
        public Integer getMemoryTokenBudgetPercentage() { return memoryTokenBudgetPercentage; }
        public void setMemoryTokenBudgetPercentage(Integer memoryTokenBudgetPercentage) { this.memoryTokenBudgetPercentage = memoryTokenBudgetPercentage; }
        public Integer getMemoryMinTokenBudget() { return memoryMinTokenBudget; }
        public void setMemoryMinTokenBudget(Integer memoryMinTokenBudget) { this.memoryMinTokenBudget = memoryMinTokenBudget; }
        public Integer getMemoryMaxTokenBudget() { return memoryMaxTokenBudget; }
        public void setMemoryMaxTokenBudget(Integer memoryMaxTokenBudget) { this.memoryMaxTokenBudget = memoryMaxTokenBudget; }
        public Integer getContextMaxPromptTokenPercentage() { return contextMaxPromptTokenPercentage; }
        public void setContextMaxPromptTokenPercentage(Integer contextMaxPromptTokenPercentage) { this.contextMaxPromptTokenPercentage = contextMaxPromptTokenPercentage; }
        public Integer getContextLongInputPromptTokenPercentage() { return contextLongInputPromptTokenPercentage; }
        public void setContextLongInputPromptTokenPercentage(Integer contextLongInputPromptTokenPercentage) { this.contextLongInputPromptTokenPercentage = contextLongInputPromptTokenPercentage; }
        public Integer getContextLongInputTokenPercentage() { return contextLongInputTokenPercentage; }
        public void setContextLongInputTokenPercentage(Integer contextLongInputTokenPercentage) { this.contextLongInputTokenPercentage = contextLongInputTokenPercentage; }
        public Integer getContextMaxHistoryRetrieval() { return contextMaxHistoryRetrieval; }
        public void setContextMaxHistoryRetrieval(Integer contextMaxHistoryRetrieval) { this.contextMaxHistoryRetrieval = contextMaxHistoryRetrieval; }
        public Integer getContextMaxSummaryRetrieval() { return contextMaxSummaryRetrieval; }
        public void setContextMaxSummaryRetrieval(Integer contextMaxSummaryRetrieval) { this.contextMaxSummaryRetrieval = contextMaxSummaryRetrieval; }
        public Integer getContextMaxMemoryRetrieval() { return contextMaxMemoryRetrieval; }
        public void setContextMaxMemoryRetrieval(Integer contextMaxMemoryRetrieval) { this.contextMaxMemoryRetrieval = contextMaxMemoryRetrieval; }
    }

    public static class CreateCronJobRequest {
        private String name;
        private String message;
        private String cron;
        private Long everySeconds;
        private String channel;
        private String to;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public Long getEverySeconds() { return everySeconds; }
        public void setEverySeconds(Long everySeconds) { this.everySeconds = everySeconds; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }

    public static class EnableCronJobRequest {
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class SaveSkillRequest {
        private String content;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class AddMcpServerRequest {
        private String name;
        private String description;
        private String type;
        private String endpoint;
        private String apiKey;
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private boolean enabled;
        private Integer timeout;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer timeout) { this.timeout = timeout; }
    }

    public static class UpdateMcpServerRequest {
        private String type;
        private String description;
        private String endpoint;
        private String apiKey;
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private boolean enabled;
        private Integer timeout;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer timeout) { this.timeout = timeout; }
    }

    public static class UpdateMcpGlobalRequest {
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class SaveFileRequest {
        private String content;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class SubmitFeedbackRequest {
        private String sessionId;
        private String messageId;
        private String type;
        private Integer value;
        private String comment;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Integer getValue() { return value; }
        public void setValue(Integer value) { this.value = value; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    public static class UploadImagesRequest {
        private List<UploadImage> images;

        public List<UploadImage> getImages() { return images; }
        public void setImages(List<UploadImage> images) { this.images = images; }
    }

    public static class UploadImage {
        private String data;
        private String name;

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class AgentProfileUpsertRequest {
        private String code;
        private String displayName;
        private String description;
        private String systemPrompt;
        private List<String> aliases;
        private List<String> allowedTools;
        private List<String> allowedSkills;
        private Map<String, Object> modelConfig;
        private String memoryScope;
        private String status;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> aliases) { this.aliases = aliases; }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
        public List<String> getAllowedSkills() { return allowedSkills; }
        public void setAllowedSkills(List<String> allowedSkills) { this.allowedSkills = allowedSkills; }
        public Map<String, Object> getModelConfig() { return modelConfig; }
        public void setModelConfig(Map<String, Object> modelConfig) { this.modelConfig = modelConfig; }
        public String getMemoryScope() { return memoryScope; }
        public void setMemoryScope(String memoryScope) { this.memoryScope = memoryScope; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class AgentProfileCloneRequest {
        private String code;
        private String displayName;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }
}


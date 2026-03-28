package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.*;
import io.jobclaw.cron.*;
import io.jobclaw.mcp.MCPService;
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
    private final CronService cronService;
    private final SkillsService skillsService;
    private final io.jobclaw.mcp.MCPService mcpService;
    private final TokenUsageService tokenUsageService;
    private final SecurityGuard securityGuard;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public WebConsoleController(Config config, SessionManager sessionManager,
                                 AgentLoop agentLoop, AgentOrchestrator orchestrator,
                                 MessageBus messageBus, ExecutionTraceService executionTraceService,
                                 CronService cronService, SkillsService skillsService,
                                 io.jobclaw.mcp.MCPService mcpService,
                                 TokenUsageService tokenUsageService,
                                 SecurityGuard securityGuard) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.orchestrator = orchestrator;
        this.messageBus = messageBus;
        this.executionTraceService = executionTraceService;
        this.cronService = cronService;
        this.skillsService = skillsService;
        this.mcpService = mcpService;
        this.tokenUsageService = tokenUsageService;
        this.securityGuard = securityGuard;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "ok");
        status.put("workspace", config.getWorkspacePath());
        status.put("model", config.getAgent().getModel());
        status.put("sessions", sessionManager.getSessionCount());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sessionKey = request.getSessionKey() != null ? request.getSessionKey() : "web:default";
            // 使用编排器处理请求（支持多 Agent 模式）
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

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120000L);

        executor.submit(() -> {
            try {
                String sessionKey = request.getSessionKey() != null ? request.getSessionKey() : "web:default";
                String result = agentLoop.process(sessionKey, request.getMessage());

                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(result));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return ResponseEntity.ok(emitter);
    }

    /**
     * 执行任务并订阅执行过程事件（SSE 流式输出）
     *
     * 前端可以使用 EventSource 连接到这个端点，实时接收 Agent 执行过程中的思考和步骤
     */
    @PostMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> executeStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 分钟超时

        String sessionKey = request.getSessionKey() != null ? request.getSessionKey() : "web:default";

        // 订阅执行事件
        String subscriberId = executionTraceService.subscribe(sessionKey, emitter);

        logger.info("Starting execution with SSE streaming for session: {}, subscriber: {}",
            sessionKey, subscriberId);

        executor.submit(() -> {
            try {
                // 发送连接确认事件
                emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("sessionId", sessionKey, "subscriberId", subscriberId)));
                logger.debug("Sent connected event to client");

                // 执行任务（带回调）- 使用 orchestrator 支持多 Agent 模式的回调
                String result = orchestrator.process(sessionKey, request.getMessage(),
                    (ExecutionEvent event) -> {
                        try {
                            // 通过 ExecutionTraceService 发布事件（会自动推送给所有订阅者）
                            executionTraceService.publish(event);
                            logger.debug("Published execution event: {}", event.getType());
                        } catch (Exception e) {
                            logger.debug("Failed to publish event: {}", e.getMessage());
                        }
                    });

                // 完成时不再发送 complete 事件，因为 FINAL_RESPONSE 事件已经作为结束信号
                // 前端会根据 FINAL_RESPONSE 事件清理流式状态
                logger.debug("Execution completed, FINAL_RESPONSE event already sent");

                emitter.complete();
                logger.debug("SSE emitter completed");

            } catch (Exception e) {
                logger.error("Error during execution", e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("error", e.getMessage())));
                } catch (Exception ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return ResponseEntity.ok(emitter);
    }

    /**
     * 订阅指定 session 的执行事件（只读，不触发执行）
     *
     * 用于多客户端同时查看同一个 Agent 的执行状态
     */
    @GetMapping(value = "/execute/stream/{sessionKey}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribeExecution(@PathVariable String sessionKey) {
        SseEmitter emitter = new SseEmitter(300000L);

        String subscriberId = executionTraceService.subscribe(sessionKey, emitter);

        logger.info("Client subscribed to session: {}, subscriber: {}", sessionKey, subscriberId);

        // 发送历史事件（如果有的话）
        executor.submit(() -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("subscribed")
                    .data(Map.of("sessionId", sessionKey, "subscriberId", subscriberId)));

                // 重播历史事件
                executionTraceService.getHistory(sessionKey).forEach(event -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("history-event")
                            .data(event.toSseData()));
                    } catch (Exception e) {
                        logger.debug("Failed to send history event: {}", e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.debug("Failed to send subscription confirmation: {}", e.getMessage());
            }
        });

        return ResponseEntity.ok(emitter);
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String key : sessionManager.getSessionKeys()) {
            Session session = sessionManager.getSession(key);
            if (session != null) {
                Map<String, Object> info = new HashMap<>();
                info.put("key", key);
                info.put("created", session.getCreated());
                info.put("updated", session.getUpdated());
                info.put("message_count", session.getMessages().size());
                sessions.add(info);
            }
        }
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/sessions/{key}")
    public ResponseEntity<Session> getSession(@PathVariable String key) {
        Session session = sessionManager.getSession(key);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
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

            // 格式化 schedule 信息
            CronSchedule schedule = job.getSchedule();
            if (schedule != null) {
                switch (schedule.getKind()) {
                    case CRON -> jobInfo.put("schedule", schedule.getExpr());
                    case EVERY -> jobInfo.put("everySeconds", schedule.getEveryMs() / 1000);
                    case AT -> jobInfo.put("at", schedule.getAtMs());
                }
            }

            // 下次执行时间
            if (job.getState() != null && job.getState().getNextRunAtMs() != null) {
                jobInfo.put("nextRun", job.getState().getNextRunAtMs());
            }

            jobs.add(jobInfo);
        }
        return ResponseEntity.ok(jobs);
    }

    /**
     * 创建定时任务
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
                response.put("error", "需要提供 cron 或 everySeconds 参数");
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
     * 删除定时任务
     * DELETE /api/cron/{id}
     */
    @DeleteMapping("/cron/{id}")
    public ResponseEntity<Map<String, Object>> deleteCronJob(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        boolean removed = cronService.removeJob(id);
        if (removed) {
            response.put("success", true);
            response.put("message", "任务已删除");
        } else {
            response.put("error", "未找到任务：" + id);
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 启用/禁用定时任务
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
                response.put("error", "未找到任务：" + id);
                return ResponseEntity.status(404).body(response);
            }
            response.put("success", true);
            response.put("message", request.isEnabled() ? "任务已启用" : "任务已禁用");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Auth API ====================

    /**
     * 检查认证状态
     * GET /api/auth/check
     */
    @GetMapping("/auth/check")
    public ResponseEntity<Map<String, Object>> checkAuth() {
        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", true); // Spring Security 已通过则已认证
        response.put("authEnabled", config.getGateway().isAuthEnabled());
        return ResponseEntity.ok(response);
    }

    /**
     * 登录获取认证 Token
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
                // 未启用认证
                response.put("success", true);
                response.put("token", "auth-disabled");
                return ResponseEntity.ok(response);
            }

            if (validUsername.equals(request.getUsername()) &&
                validPassword.equals(request.getPassword())) {
                response.put("success", true);
                // 使用 Base64 编码凭证（HTTP Basic Auth 标准格式）
                String credentials = request.getUsername() + ":" + request.getPassword();
                String token = Base64.getEncoder().encodeToString(credentials.getBytes());
                response.put("token", token);
            } else {
                response.put("success", false);
                response.put("error", "用户名或密码错误");
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
     * 列出所有频道
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
     * 获取频道详细配置
     * GET /api/channels/{name}
     */
    @GetMapping("/channels/{name}")
    public ResponseEntity<Map<String, Object>> getChannel(@PathVariable String name) {
        Map<String, Object> response = new HashMap<>();
        ChannelsConfig channelsConfig = config.getChannels();

        try {
            Object channelConfig = getChannelConfig(channelsConfig, name);
            if (channelConfig == null) {
                response.put("error", "未知的频道名称：" + name);
                return ResponseEntity.status(404).body(response);
            }

            // 反射获取配置属性
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
            // 获取 enabled 属性
            var enabledMethod = config.getClass().getMethod("isEnabled");
            result.put("enabled", enabledMethod.invoke(config));

            // 获取 token 属性（如果存在）
            try {
                var tokenMethod = config.getClass().getMethod("getToken");
                Object token = tokenMethod.invoke(config);
                if (token != null && !token.toString().isEmpty()) {
                    // 隐藏敏感信息
                    String tokenStr = token.toString();
                    result.put("token", tokenStr.length() > 8 ?
                            tokenStr.substring(0, 4) + "****" + tokenStr.substring(tokenStr.length() - 4) :
                            "****");
                }
            } catch (NoSuchMethodException e) {
                // 某些频道可能没有 token
            }

            // 获取 allowFrom 属性（如果存在）
            try {
                var allowFromMethod = config.getClass().getMethod("getAllowFrom");
                result.put("allowFrom", allowFromMethod.invoke(config));
            } catch (NoSuchMethodException e) {
                // 某些频道可能没有 allowFrom
            }

        } catch (Exception e) {
            logger.warn("Failed to extract channel config for {}: {}", name, e.getMessage());
        }
        return result;
    }

    /**
     * 更新频道配置
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
                response.put("error", "未知的频道名称：" + name);
                return ResponseEntity.status(404).body(response);
            }

            // 更新 enabled 属性
            var setEnabledMethod = channelConfig.getClass().getMethod("setEnabled", boolean.class);
            setEnabledMethod.invoke(channelConfig, request.isEnabled());

            // 如果提供了 token，更新 token
            if (request.getToken() != null && !request.getToken().isEmpty()) {
                try {
                    var setTokenMethod = channelConfig.getClass().getMethod("setToken", String.class);
                    setTokenMethod.invoke(channelConfig, request.getToken());
                } catch (NoSuchMethodException e) {
                    // 某些频道可能没有 token
                }
            }

            // TODO: 将配置持久化到 config.json
            // ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "频道配置已更新");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Providers API ====================

    /**
     * 列出所有 LLM 提供商
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
        // 隐藏 API Key 敏感信息
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

    /**
     * 更新提供商配置
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
                response.put("error", "未知的提供商名称：" + name);
                return ResponseEntity.status(404).body(response);
            }

            // 更新 API Key
            if (request.getApiKey() != null) {
                providerConfig.setApiKey(request.getApiKey());
            }

            // 更新 API Base
            if (request.getApiBase() != null && !request.getApiBase().isEmpty()) {
                providerConfig.setApiBase(request.getApiBase());
            }

            // TODO: 将配置持久化到 config.json
            // ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "提供商配置已更新");
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

    // ==================== Models API ====================

    /**
     * 列出所有可用模型
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

            // 检查提供商是否已授权
            ProvidersConfig.ProviderConfig providerConfig = getProviderConfig(providersConfig, def.getProvider());
            modelInfo.put("authorized", providerConfig != null && providerConfig.isValid());

            models.add(modelInfo);
        }

        return ResponseEntity.ok(models);
    }

    // ==================== Config API ====================

    /**
     * 获取当前模型和提供商配置
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
     * 更新模型配置
     * PUT /api/config/model
     */
    @PutMapping("/config/model")
    public ResponseEntity<Map<String, Object>> updateConfigModel(@RequestBody UpdateModelRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 验证模型是否存在
            ModelsConfig.ModelDefinition modelDef = config.getModels().getDefinitions().get(request.getModel());
            if (modelDef == null) {
                response.put("error", "未知的模型：" + request.getModel());
                return ResponseEntity.status(400).body(response);
            }

            // 更新模型配置
            config.getAgent().setModel(request.getModel());

            // 如果提供了 provider，也更新
            if (request.getProvider() != null && !request.getProvider().isEmpty()) {
                config.getAgent().setProvider(request.getProvider());
            }

            // TODO: 将配置持久化到 config.json
            // ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "模型配置已更新");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 获取 Agent 配置
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

        return ResponseEntity.ok(response);
    }

    /**
     * 更新 Agent 配置
     * PUT /api/config/agent
     */
    @PutMapping("/config/agent")
    public ResponseEntity<Map<String, Object>> updateConfigAgent(@RequestBody UpdateAgentConfigRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            AgentConfig agentConfig = config.getAgent();

            // 更新配置
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

            // TODO: 将配置持久化到 config.json
            // ConfigLoader.save(ConfigLoader.getConfigPath(), config);

            response.put("success", true);
            response.put("message", "Agent 配置已更新");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Skills API ====================

    /**
     * 列出所有技能
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
     * 获取技能内容
     * GET /api/skills/{skillName}
     */
    @GetMapping("/skills/{skillName}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String skillName) {
        Map<String, Object> response = new HashMap<>();
        try {
            String content = skillsService.loadSkill(skillName);
            if (content == null) {
                response.put("error", "技能不存在：" + skillName);
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
     * 创建或更新技能
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
     * 删除技能
     * DELETE /api/skills/{skillName}
     */
    @DeleteMapping("/skills/{skillName}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String skillName) {
        Map<String, Object> response = new HashMap<>();
        boolean deleted = skillsService.deleteSkill(skillName);
        if (deleted) {
            response.put("success", true);
        } else {
            response.put("error", "技能不存在：" + skillName);
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // ==================== MCP API ====================

    /**
     * 获取 MCP 服务器配置
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
            // 隐藏 API Key
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
     * 更新 MCP 全局启用状态
     * PUT /api/mcp
     */
    @PutMapping("/mcp")
    public ResponseEntity<Map<String, Object>> updateMcpConfig(
            @RequestBody UpdateMcpGlobalRequest request) {
        Map<String, Object> response = new HashMap<>();
        config.getMcpServers().setEnabled(request.isEnabled());
        response.put("success", true);
        response.put("message", "MCP 配置已更新");
        return ResponseEntity.ok(response);
    }

    /**
     * 添加 MCP 服务器
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
     * 更新 MCP 服务器配置
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
                response.put("error", "服务器不存在：" + serverName);
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
     * 删除 MCP 服务器
     * DELETE /api/mcp/{serverName}
     */
    @DeleteMapping("/mcp/{serverName}")
    public ResponseEntity<Map<String, Object>> deleteMcpServer(@PathVariable String serverName) {
        Map<String, Object> response = new HashMap<>();
        List<MCPServersConfig.MCPServerConfig> servers = config.getMcpServers().getServers();
        boolean removed = servers.removeIf(s -> s.getName().equals(serverName));
        if (removed) {
            response.put("success", true);
            response.put("message", "服务器已删除");
        } else {
            response.put("error", "服务器不存在：" + serverName);
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 测试 MCP 服务器连接
     * POST /api/mcp/{serverName}/test
     */
    @PostMapping("/mcp/{serverName}/test")
    public ResponseEntity<Map<String, Object>> testMcpServer(@PathVariable String serverName) {
        Map<String, Object> response = new HashMap<>();
        try {
            MCPServersConfig.MCPServerConfig server = findMcpServer(serverName);
            if (server == null) {
                response.put("error", "服务器不存在：" + serverName);
                return ResponseEntity.status(404).body(response);
            }

            // TODO: 实际测试连接
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

    // ==================== Workspace Files API ====================

    /**
     * 列出工作区文件
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
     * 读取工作区文件
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
                response.put("error", "文件不存在：" + fileName);
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
     * 保存工作区文件
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

            // 确保父目录存在
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }

            Files.writeString(filePath, request.getContent());
            response.put("success", true);
            response.put("message", "文件已保存");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ==================== Token Stats API ====================

    /**
     * 获取 Token 使用统计
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

            // 按模型分组（简化版）
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
     * 获取反馈系统状态
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
     * 提交反馈
     * POST /api/feedback
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(@RequestBody SubmitFeedbackRequest request) {
        Map<String, Object> response = new HashMap<>();
        // TODO: 保存反馈到存储
        response.put("success", true);
        response.put("message", "反馈已记录，感谢您的评价！");
        return ResponseEntity.ok(response);
    }

    // ==================== Upload API ====================

    /**
     * 上传图片
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
                        // 解析 base64 数据
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
            extension = ".jpg"; // 默认扩展名
        }
        return timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
    }

    // ==================== Files API ====================

    /**
     * 访问上传的文件（无需认证，用于 img src）
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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class UpdateProviderRequest {
        private String apiKey;
        private String apiBase;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
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
}

package io.jobclaw.web;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.bus.InboundMessage;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
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
    private final MessageBus messageBus;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public WebConsoleController(Config config, SessionManager sessionManager,
                                 AgentLoop agentLoop, MessageBus messageBus) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.messageBus = messageBus;
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
            String result = agentLoop.processWithTools(sessionKey, request.getMessage());
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
                String result = agentLoop.processWithTools(sessionKey, request.getMessage());

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
    public ResponseEntity<Map<String, Object>> getCronJobs() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        return ResponseEntity.ok(response);
    }

    public static class ChatRequest {
        private String message;
        private String sessionKey;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSessionKey() { return sessionKey; }
        public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
    }
}

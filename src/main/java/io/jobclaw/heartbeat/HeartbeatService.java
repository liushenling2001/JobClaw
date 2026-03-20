package io.jobclaw.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class HeartbeatService {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatService.class);

    private final ScheduledExecutorService scheduler;
    private final String memoryPath;
    private final long intervalSeconds;
    private Consumer<String> onHeartbeat;
    private volatile boolean running;

    public HeartbeatService() {
        this(Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "memory").toString(), 300);
    }

    public HeartbeatService(String memoryPath, long intervalSeconds) {
        this.memoryPath = memoryPath;
        this.intervalSeconds = intervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-service");
            t.setDaemon(true);
            return t;
        });
        this.running = false;
    }

    public void setOnHeartbeat(Consumer<String> handler) {
        this.onHeartbeat = handler;
    }

    public void start() {
        if (running) {
            return;
        }

        running = true;
        scheduler.scheduleAtFixedRate(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        logger.info("Heartbeat service started (interval: {}s)", intervalSeconds);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        logger.info("Heartbeat service stopped");
    }

    private void tick() {
        if (!running || onHeartbeat == null) {
            return;
        }

        try {
            String prompt = buildPrompt();
            onHeartbeat.accept(prompt);
        } catch (Exception e) {
            logger.error("Heartbeat tick failed", e);
        }
    }

    private String buildPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("[HEARTBEAT]\n");
        sb.append("Current time: ").append(java.time.Instant.now()).append("\n");

        try {
            String heartbeatMd = Paths.get(memoryPath, "HEARTBEAT.md").toString();
            if (Files.exists(Paths.get(heartbeatMd))) {
                String content = Files.readString(Paths.get(heartbeatMd));
                sb.append("\n[HEARTBEAT CONTEXT]\n");
                sb.append(content);
            }
        } catch (Exception e) {
            logger.warn("Failed to read HEARTBEAT.md");
        }

        sb.append("\nPlease perform a system self-check and process any pending tasks.");
        return sb.toString();
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", running);
        status.put("interval_seconds", intervalSeconds);
        return status;
    }
}

package io.jobclaw.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JobClaw 定时服务
 * 
 * 调度和执行定时任务的核心服务
 * 
 * 支持三种调度类型：
 * - AT：一次性任务（指定时间执行）
 * - EVERY：周期性任务（固定间隔）
 * - CRON：Cron 表达式任务（复杂规则）
 */
public class CronService {
    
    private static final Logger logger = LoggerFactory.getLogger(CronService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private static final long CHECK_INTERVAL_MS = 1000L;  // 1 秒检查间隔
    private static final int ID_BYTE_LENGTH = 8;
    private static final String HEX_FORMAT = "%02x";
    
    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";
    private static final String THREAD_NAME = "cron-service";
    private static final String LEGACY_CRON_FILE = "cron.json";
    private static final String CRON_DIR = "cron";
    private static final String CRON_STORE_FILE = "jobs.json";
    
    private final Path storePath;
    private final Path legacyStorePath;
    private CronStore store;
    private JobHandler onJob;
    private final ReentrantReadWriteLock lock;
    private volatile boolean running;
    private Thread runnerThread;
    
    private final CronParser cronParser;
    
    /**
     * 任务处理器接口
     */
    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception;
    }
    
    public CronService(String workspacePath) {
        this.storePath = resolveStorePath(workspacePath);
        this.legacyStorePath = resolveLegacyStorePath();
        this.lock = new ReentrantReadWriteLock();
        this.running = false;
        this.cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
        );
        loadStore();
    }
    
    /**
     * 设置任务处理器
     */
    public void setOnJob(JobHandler handler) {
        this.onJob = handler;
    }
    
    /**
     * 启动定时服务
     */
    public void start() {
        lock.writeLock().lock();
        try {
            if (running) {
                return;
            }
            
            loadStore();
            recomputeNextRuns();
            saveStoreUnsafe();
            
            running = true;
            runnerThread = new Thread(this::runLoop, THREAD_NAME);
            runnerThread.setDaemon(true);
            runnerThread.start();
            
            logger.info("Cron service started");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 停止定时服务
     */
    public void stop() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return;
            }
            
            running = false;
            if (runnerThread != null) {
                runnerThread.interrupt();
            }
            
            logger.info("Cron service stopped");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 调度循环
     */
    private void runLoop() {
        while (running) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
                checkJobs();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in cron loop", e);
            }
        }
    }
    
    /**
     * 检查并执行到期任务
     */
    private void checkJobs() {
        List<CronJob> dueJobs = collectDueJobs();
        for (CronJob job : dueJobs) {
            executeJob(job);
        }
    }
    
    /**
     * 收集到期任务
     */
    private List<CronJob> collectDueJobs() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return List.of();
            }
            
            long now = System.currentTimeMillis();
            List<CronJob> dueJobs = new ArrayList<>();
            
            for (CronJob job : store.getJobs()) {
                if (job.isEnabled() && 
                    job.getState().getNextRunAtMs() != null && 
                    job.getState().getNextRunAtMs() <= now) {
                    dueJobs.add(job);
                    job.getState().setNextRunAtMs(null);
                }
            }
            
            if (!dueJobs.isEmpty()) {
                saveStoreUnsafe();
            }
            
            return dueJobs;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 执行任务
     */
    private void executeJob(CronJob job) {
        long startTime = System.currentTimeMillis();
        String error = invokeJobHandler(job);
        updateJobState(job, startTime, error);
    }
    
    /**
     * 调用任务处理器
     */
    private String invokeJobHandler(CronJob job) {
        try {
            if (onJob != null) {
                return onJob.handle(job);
            }
            return null;
        } catch (Exception e) {
            String error = e.getMessage();
            logger.error("Job execution failed: job_id={}, error={}", job.getId(), error);
            return error;
        }
    }
    
    /**
     * 更新任务状态
     */
    private void updateJobState(CronJob job, long startTime, String error) {
        lock.writeLock().lock();
        try {
            CronJob storeJob = findJobById(job.getId());
            if (storeJob == null) {
                return;
            }
            
            storeJob.getState().setLastRunAtMs(startTime);
            storeJob.setUpdatedAtMs(System.currentTimeMillis());
            
            if (error != null) {
                storeJob.getState().setLastStatus(STATUS_ERROR);
                storeJob.getState().setLastError(error);
            } else {
                storeJob.getState().setLastStatus(STATUS_OK);
                storeJob.getState().setLastError(null);
            }
            
            handlePostExecution(storeJob);
            saveStoreUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 处理执行后逻辑
     */
    private void handlePostExecution(CronJob job) {
        if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.AT) {
            if (job.isDeleteAfterRun()) {
                removeJobUnsafe(job.getId());
            } else {
                job.setEnabled(false);
                job.getState().setNextRunAtMs(null);
            }
        } else {
            Long nextRun = computeNextRun(job.getSchedule(), System.currentTimeMillis());
            job.getState().setNextRunAtMs(nextRun);
        }
    }
    
    /**
     * 计算下次执行时间
     */
    private Long computeNextRun(CronSchedule schedule, long nowMs) {
        return switch (schedule.getKind()) {
            case AT -> computeAtNextRun(schedule, nowMs);
            case EVERY -> computeEveryNextRun(schedule, nowMs);
            case CRON -> computeCronNextRun(schedule, nowMs);
        };
    }
    
    private Long computeAtNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getAtMs() != null && schedule.getAtMs() > nowMs) {
            return schedule.getAtMs();
        }
        return null;
    }
    
    private Long computeEveryNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getEveryMs() == null || schedule.getEveryMs() <= 0) {
            return null;
        }
        return nowMs + schedule.getEveryMs();
    }
    
    private Long computeCronNextRun(CronSchedule schedule, long nowMs) {
        if (schedule.getExpr() == null || schedule.getExpr().isEmpty()) {
            return null;
        }
        
        try {
            Cron cron = cronParser.parse(schedule.getExpr());
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(nowMs), 
                ZoneId.systemDefault()
            );
            Optional<ZonedDateTime> next = executionTime.nextExecution(now);
            return next.map(zonedDateTime -> zonedDateTime.toInstant().toEpochMilli())
                       .orElse(null);
        } catch (Exception e) {
            logger.error("Failed to compute next run for cron expr: {}", schedule.getExpr(), e);
            return null;
        }
    }
    
    /**
     * 重新计算所有任务的下次执行时间
     */
    private void recomputeNextRuns() {
        long now = System.currentTimeMillis();
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }
    
    /**
     * 加载存储
     */
    private void loadStore() {
        store = new CronStore();
        
        try {
            migrateLegacyStoreIfNeeded();
            if (Files.exists(storePath)) {
                String json = Files.readString(storePath);
                JsonNode root = objectMapper.readTree(json);
                int version = root.path("version").asInt(-1);

                if (version != CronStore.CURRENT_VERSION) {
                    logger.warn("Cron store version mismatch at {}. expected={}, actual={}. Resetting store.",
                            storePath, CronStore.CURRENT_VERSION, version);
                    resetStoreUnsafe();
                    return;
                }

                CronStore loaded = objectMapper.treeToValue(root, CronStore.class);
                if (loaded == null || loaded.getJobs() == null) {
                    resetStoreUnsafe();
                    return;
                }
                loaded.setVersion(CronStore.CURRENT_VERSION);
                store = loaded;
            }
        } catch (Exception e) {
            logger.warn("Failed to load cron store at {}. Resetting to empty latest format.", storePath, e);
            resetStoreUnsafe();
        }
    }
    
    /**
     * 保存存储（无锁）
     */
    private void saveStoreUnsafe() {
        try {
            Files.createDirectories(storePath.getParent());
            store.setVersion(CronStore.CURRENT_VERSION);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(store);
            Files.writeString(storePath, json);
        } catch (Exception e) {
            logger.error("Failed to save cron store", e);
        }
    }

    private void resetStoreUnsafe() {
        store = new CronStore();
        store.setVersion(CronStore.CURRENT_VERSION);
        store.setJobs(new ArrayList<>());
        saveStoreUnsafe();
    }

    private Path resolveStorePath(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", CRON_DIR, CRON_STORE_FILE);
        }
        return Paths.get(workspacePath, CRON_DIR, CRON_STORE_FILE);
    }

    private Path resolveLegacyStorePath() {
        return Paths.get(System.getProperty("user.home"), ".jobclaw", LEGACY_CRON_FILE);
    }

    private void migrateLegacyStoreIfNeeded() {
        try {
            if (Files.exists(storePath) || !Files.exists(legacyStorePath)) {
                return;
            }
            Files.createDirectories(storePath.getParent());
            Files.move(legacyStorePath, storePath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Migrated legacy cron store: {} -> {}", legacyStorePath, storePath);
        } catch (Exception moveError) {
            logger.warn("Failed to move legacy cron store, trying copy: {}", moveError.getMessage());
            try {
                Files.createDirectories(storePath.getParent());
                Files.copy(legacyStorePath, storePath, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Copied legacy cron store: {} -> {}", legacyStorePath, storePath);
            } catch (Exception copyError) {
                logger.error("Failed to migrate legacy cron store", copyError);
            }
        }
    }
    
    /**
     * 添加任务
     */
    public CronJob addJob(String name, CronSchedule schedule, String message,
                          String channel, String to) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean deleteAfterRun = schedule.getKind() == CronSchedule.ScheduleKind.AT;
            
            CronJob job = createJob(name, schedule, message, channel, to, now, deleteAfterRun);
            
            store.getJobs().add(job);
            saveStoreUnsafe();
            
            logger.info("Added cron job: id={}, name={}, kind={}", 
                       job.getId(), name, schedule.getKind());
            
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 创建任务对象
     */
    private CronJob createJob(String name, CronSchedule schedule, String message,
                             String channel, String to, long now, boolean deleteAfterRun) {
        CronJob job = new CronJob();
        job.setId(generateId());
        job.setName(name);
        job.setEnabled(true);
        job.setSchedule(schedule);
        job.setPayload(new CronPayload(message, channel, to));
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(deleteAfterRun);
        job.getState().setNextRunAtMs(computeNextRun(schedule, now));
        return job;
    }
    
    /**
     * 删除任务
     */
    public boolean removeJob(String jobId) {
        lock.writeLock().lock();
        try {
            return removeJobUnsafe(jobId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private boolean removeJobUnsafe(String jobId) {
        boolean removed = store.getJobs().removeIf(j -> j.getId().equals(jobId));
        if (removed) {
            saveStoreUnsafe();
        }
        return removed;
    }
    
    /**
     * 启用/禁用任务
     */
    public CronJob enableJob(String jobId, boolean enabled) {
        lock.writeLock().lock();
        try {
            CronJob job = findJobById(jobId);
            if (job == null) {
                return null;
            }
            
            job.setEnabled(enabled);
            job.setUpdatedAtMs(System.currentTimeMillis());
            
            if (enabled) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), System.currentTimeMillis()));
            } else {
                job.getState().setNextRunAtMs(null);
            }
            
            saveStoreUnsafe();
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 列出所有任务
     */
    public List<CronJob> listJobs(boolean includeDisabled) {
        lock.readLock().lock();
        try {
            if (includeDisabled) {
                return new ArrayList<>(store.getJobs());
            }
            return store.getJobs().stream()
                    .filter(CronJob::isEnabled)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 查找任务
     */
    private CronJob findJobById(String jobId) {
        for (CronJob j : store.getJobs()) {
            if (j.getId().equals(jobId)) {
                return j;
            }
        }
        return null;
    }
    
    /**
     * 生成任务 ID
     */
    private String generateId() {
        byte[] bytes = new byte[ID_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(HEX_FORMAT, b));
        }
        return sb.toString();
    }
    
    /**
     * 获取服务状态
     */
    public Map<String, Object> status() {
        lock.readLock().lock();
        try {
            long enabledCount = store.getJobs().stream()
                    .filter(CronJob::isEnabled)
                    .count();
            
            Map<String, Object> status = new HashMap<>();
            status.put("enabled", running);
            status.put("jobs", store.getJobs().size());
            status.put("enabled_jobs", enabledCount);
            return status;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public boolean isRunning() {
        return running;
    }
}

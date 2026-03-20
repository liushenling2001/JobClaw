package io.jobclaw.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class CronService {

    private static final Logger logger = LoggerFactory.getLogger(CronService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final long CHECK_INTERVAL_MS = 1000L;
    private static final int ID_BYTE_LENGTH = 8;
    private static final String HEX_FORMAT = "%02x";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";
    private static final String THREAD_NAME = "cron-service";

    private final String storePath;
    private CronStore store;
    private JobHandler onJob;
    private final ReentrantReadWriteLock lock;
    private volatile boolean running;
    private Thread runnerThread;

    private final CronParser cronParser;

    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception;
    }

    public CronService() {
        this(Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace", "cron", "jobs.json").toString(), null);
    }

    public CronService(String storePath, JobHandler onJob) {
        this.storePath = storePath;
        this.onJob = onJob;
        this.lock = new ReentrantReadWriteLock();
        this.running = false;
        this.cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
        loadStore();
    }

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

    private void checkJobs() {
        List<CronJob> dueJobs = collectDueJobs();
        for (CronJob job : dueJobs) {
            executeJob(job);
        }
    }

    private List<CronJob> collectDueJobs() {
        lock.writeLock().lock();
        try {
            if (!running) {
                return List.of();
            }

            long now = System.currentTimeMillis();
            List<CronJob> dueJobs = new ArrayList<>();

            for (CronJob job : store.getJobs()) {
                if (job.isEnabled() && job.getState().getNextRunAtMs() != null && job.getState().getNextRunAtMs() <= now) {
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

    private void executeJob(CronJob job) {
        long startTime = System.currentTimeMillis();
        String error = invokeJobHandler(job);
        updateJobState(job, startTime, error);
    }

    private String invokeJobHandler(CronJob job) {
        try {
            if (onJob != null) {
                onJob.handle(job);
            }
            return null;
        } catch (Exception e) {
            String error = e.getMessage();
            logger.error("Job execution failed: {}", error);
            return error;
        }
    }

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

    private void handlePostExecution(CronJob job) {
        if (CronSchedule.ScheduleKind.AT == job.getSchedule().getKind()) {
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

    private CronJob findJobById(String jobId) {
        for (CronJob j : store.getJobs()) {
            if (j.getId().equals(jobId)) {
                return j;
            }
        }
        return null;
    }

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
            ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
            Optional<ZonedDateTime> next = executionTime.nextExecution(now);
            return next.map(zonedDateTime -> zonedDateTime.toInstant().toEpochMilli()).orElse(null);
        } catch (Exception e) {
            logger.error("Failed to compute next run for cron expr: {}", schedule.getExpr(), e);
            return null;
        }
    }

    private void recomputeNextRuns() {
        long now = System.currentTimeMillis();
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }

    private void loadStore() {
        store = new CronStore();
        try {
            Path path = Paths.get(storePath);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                store = objectMapper.readValue(json, CronStore.class);
                if (store.getJobs() == null) {
                    store.setJobs(new ArrayList<>());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load cron store, using empty");
            store = new CronStore();
        }
    }

    private void saveStoreUnsafe() {
        try {
            Path path = Paths.get(storePath);
            Files.createDirectories(path.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(store);
            Files.writeString(path, json);
        } catch (Exception e) {
            logger.error("Failed to save cron store", e);
        }
    }

    public CronJob addJob(String name, CronSchedule schedule, String message, String channel, String to) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean deleteAfterRun = CronSchedule.ScheduleKind.AT == schedule.getKind();

            CronJob job = createJob(name, schedule, message, channel, to, now, deleteAfterRun);
            store.getJobs().add(job);
            saveStoreUnsafe();

            logger.info("Added cron job: {}", job.getId());
            return job;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private CronJob createJob(String name, CronSchedule schedule, String message, String channel, String to, long now, boolean deleteAfterRun) {
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

    public List<CronJob> listJobs(boolean includeDisabled) {
        lock.readLock().lock();
        try {
            if (includeDisabled) {
                return new ArrayList<>(store.getJobs());
            }
            return store.getJobs().stream().filter(CronJob::isEnabled).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<String, Object> status() {
        lock.readLock().lock();
        try {
            long enabledCount = store.getJobs().stream().filter(CronJob::isEnabled).count();
            Map<String, Object> status = new HashMap<>();
            status.put("enabled", running);
            status.put("jobs", store.getJobs().size());
            status.put("enabled_jobs", enabledCount);
            return status;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setOnJob(JobHandler handler) {
        this.onJob = handler;
    }

    public void load() {
        lock.writeLock().lock();
        try {
            loadStore();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private String generateId() {
        byte[] bytes = new byte[ID_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(HEX_FORMAT, b));
        }
        return sb.toString();
    }

    public boolean isRunning() {
        return running;
    }
}

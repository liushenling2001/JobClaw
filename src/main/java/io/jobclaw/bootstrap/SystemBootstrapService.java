package io.jobclaw.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.jobclaw.cron.CronJob;
import io.jobclaw.cron.CronJobState;
import io.jobclaw.cron.CronPayload;
import io.jobclaw.cron.CronSchedule;
import io.jobclaw.cron.CronStore;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

@Component
public class SystemBootstrapService {

    public static final String EXPERIENCE_REVIEW_JOB_ID = "builtin-experience-review-daily";
    private static final String INTERNAL_TYPE = "INTERNAL";
    private static final String EXPERIENCE_REVIEW_ACTION = "experience_review";
    private static final String EXPERIENCE_REVIEW_CRON = "0 1 * * *";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void initializeOnboardDefaults(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalArgumentException("workspacePath is required");
        }
        try {
            Path workspace = Path.of(workspacePath);
            ensureExperienceDirectories(workspace);
            ensureExperienceReviewCronJob(workspace);
            writeBootstrapState(workspace);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize onboard defaults: " + e.getMessage(), e);
        }
    }

    private void ensureExperienceDirectories(Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".jobclaw"));
        Files.createDirectories(workspace.resolve(".jobclaw").resolve("agents"));
        Files.createDirectories(workspace.resolve(".jobclaw").resolve("checkpoints"));
        Files.createDirectories(workspace.resolve(".jobclaw").resolve("experience"));
        Files.createDirectories(workspace.resolve(".jobclaw").resolve("learning"));
        Files.createDirectories(workspace.resolve(".jobclaw").resolve("workflows"));
        Files.createDirectories(workspace.resolve("sessions").resolve("conversation"));
        Files.createDirectories(workspace.resolve("cron"));
    }

    private void ensureExperienceReviewCronJob(Path workspace) throws Exception {
        Path storePath = workspace.resolve("cron").resolve("jobs.json");
        CronStore store = loadCronStore(storePath);
        boolean exists = store.getJobs().stream()
                .anyMatch(job -> EXPERIENCE_REVIEW_JOB_ID.equals(job.getId()));
        if (exists) {
            return;
        }
        store.getJobs().add(createExperienceReviewJob());
        saveCronStore(storePath, store);
    }

    private CronStore loadCronStore(Path storePath) throws Exception {
        if (!Files.exists(storePath)) {
            CronStore store = new CronStore();
            store.setJobs(new ArrayList<>());
            return store;
        }
        String json = Files.readString(storePath);
        if (json == null || json.isBlank()) {
            CronStore store = new CronStore();
            store.setJobs(new ArrayList<>());
            return store;
        }
        CronStore store = objectMapper.readValue(json, CronStore.class);
        if (store.getJobs() == null) {
            store.setJobs(new ArrayList<>());
        }
        store.setVersion(CronStore.CURRENT_VERSION);
        return store;
    }

    private void saveCronStore(Path storePath, CronStore store) throws Exception {
        Files.createDirectories(storePath.getParent());
        store.setVersion(CronStore.CURRENT_VERSION);
        objectMapper.writeValue(storePath.toFile(), store);
    }

    private CronJob createExperienceReviewJob() {
        long now = System.currentTimeMillis();
        CronJob job = new CronJob();
        job.setId(EXPERIENCE_REVIEW_JOB_ID);
        job.setName("每日经验整理");
        job.setEnabled(true);
        job.setType(INTERNAL_TYPE);
        job.setAction(EXPERIENCE_REVIEW_ACTION);
        job.setBuiltin(true);
        job.setSchedule(CronSchedule.cron(EXPERIENCE_REVIEW_CRON));
        job.setPayload(new CronPayload("Run daily JobClaw experience review", null, null));
        job.setState(new CronJobState());
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(false);
        return job;
    }

    private void writeBootstrapState(Path workspace) throws Exception {
        Path statePath = workspace.resolve(".jobclaw").resolve("bootstrap-state.json");
        if (Files.exists(statePath)) {
            return;
        }
        Files.createDirectories(statePath.getParent());
        String json = """
                {
                  "initialized": true,
                  "version": 1,
                  "initializedAt": "%s",
                  "features": {
                    "agentCatalog": true,
                    "taskCheckpoints": true,
                    "builtinExperienceReviewSkill": true,
                    "builtinExperienceReviewCronJob": true,
                    "experienceMemory": true
                  }
                }
                """.formatted(Instant.now().toString());
        Files.writeString(statePath, json);
    }
}

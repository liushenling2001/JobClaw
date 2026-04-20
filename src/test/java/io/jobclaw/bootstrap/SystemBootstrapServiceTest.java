package io.jobclaw.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.cron.CronStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemBootstrapServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateExperienceReviewCronJobOnOnboardBootstrap() throws Exception {
        SystemBootstrapService service = new SystemBootstrapService();

        service.initializeOnboardDefaults(tempDir.toString());

        Path storePath = tempDir.resolve("cron").resolve("jobs.json");
        assertTrue(Files.exists(storePath));

        CronStore store = new ObjectMapper().readValue(storePath.toFile(), CronStore.class);
        assertEquals(1, store.getJobs().size());
        var job = store.getJobs().get(0);
        assertEquals(SystemBootstrapService.EXPERIENCE_REVIEW_JOB_ID, job.getId());
        assertEquals("INTERNAL", job.getType());
        assertEquals("experience_review", job.getAction());
        assertTrue(job.isBuiltin());
        assertEquals("0 1 * * *", job.getSchedule().getExpr());
    }

    @Test
    void shouldNotDuplicateExperienceReviewCronJob() throws Exception {
        SystemBootstrapService service = new SystemBootstrapService();

        service.initializeOnboardDefaults(tempDir.toString());
        service.initializeOnboardDefaults(tempDir.toString());

        CronStore store = new ObjectMapper().readValue(
                tempDir.resolve("cron").resolve("jobs.json").toFile(),
                CronStore.class
        );
        long count = store.getJobs().stream()
                .filter(job -> SystemBootstrapService.EXPERIENCE_REVIEW_JOB_ID.equals(job.getId()))
                .count();
        assertEquals(1, count);
    }

    @Test
    void shouldCreateExperienceDirectoriesAndBootstrapState() {
        SystemBootstrapService service = new SystemBootstrapService();

        service.initializeOnboardDefaults(tempDir.toString());

        assertTrue(Files.exists(tempDir.resolve(".jobclaw").resolve("experience")));
        assertTrue(Files.exists(tempDir.resolve(".jobclaw").resolve("learning")));
        assertTrue(Files.exists(tempDir.resolve(".jobclaw").resolve("workflows")));
        assertTrue(Files.exists(tempDir.resolve(".jobclaw").resolve("agents")));
        assertTrue(Files.exists(tempDir.resolve(".jobclaw").resolve("checkpoints")));
        assertTrue(Files.exists(tempDir.resolve("sessions").resolve("conversation")));
        assertTrue(Files.exists(tempDir.resolve(".jobclaw").resolve("bootstrap-state.json")));
    }
}

package io.jobclaw.cron;

import io.jobclaw.agent.experience.ExperienceReviewJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CronJobDispatcherTest {

    @Test
    void shouldDispatchExperienceReviewInternalJob() throws Exception {
        ExperienceReviewJob reviewJob = mock(ExperienceReviewJob.class);
        when(reviewJob.run()).thenReturn("review-ok");
        CronJobDispatcher dispatcher = new CronJobDispatcher(reviewJob);
        CronJob job = new CronJob();
        job.setType(CronJobDispatcher.TYPE_INTERNAL);
        job.setAction(CronJobDispatcher.ACTION_EXPERIENCE_REVIEW);

        String result = dispatcher.handle(job);

        assertEquals("review-ok", result);
        verify(reviewJob).run();
    }

    @Test
    void shouldHandleLegacyMessageJobWhenTypeMissing() throws Exception {
        ExperienceReviewJob reviewJob = mock(ExperienceReviewJob.class);
        CronJobDispatcher dispatcher = new CronJobDispatcher(reviewJob);
        CronJob job = new CronJob();
        job.setId("message-a");
        job.setName("message job");
        job.setPayload(new CronPayload("hello", "web", "default"));

        String result = dispatcher.handle(job);

        assertEquals("Job executed: message job", result);
    }
}

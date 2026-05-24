package io.jobclaw.cron;

import io.jobclaw.agent.experience.ExperienceReviewJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CronJobDispatcher implements CronService.JobHandler {

    public static final String TYPE_INTERNAL = "INTERNAL";
    public static final String TYPE_MESSAGE = "MESSAGE";
    public static final String ACTION_EXPERIENCE_REVIEW = "experience_review";

    private static final Logger logger = LoggerFactory.getLogger(CronJobDispatcher.class);

    private final ExperienceReviewJob experienceReviewJob;

    public CronJobDispatcher(ExperienceReviewJob experienceReviewJob) {
        this.experienceReviewJob = experienceReviewJob;
    }

    @Override
    public String handle(CronJob job) throws Exception {
        if (job == null) {
            return "Cron job is null";
        }
        String type = job.getType() == null || job.getType().isBlank() ? TYPE_MESSAGE : job.getType();
        if (TYPE_INTERNAL.equalsIgnoreCase(type)) {
            return handleInternal(job);
        }
        return handleMessage(job);
    }

    private String handleInternal(CronJob job) {
        if (ACTION_EXPERIENCE_REVIEW.equals(job.getAction())) {
            return experienceReviewJob.run();
        }
        String message = "Unsupported internal cron action: " + job.getAction();
        logger.warn(message);
        return message;
    }

    private String handleMessage(CronJob job) {
        CronPayload payload = job.getPayload();
        logger.info("Executing message cron job: id={}, name={}, channel={}, to={}",
                job.getId(),
                job.getName(),
                payload != null ? payload.getChannel() : null,
                payload != null ? payload.getTo() : null);
        return "Job executed: " + job.getName();
    }
}

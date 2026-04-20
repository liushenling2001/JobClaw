package io.jobclaw.agent.experience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExperienceReviewJob {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceReviewJob.class);

    private final ExperienceReviewService experienceReviewService;

    public ExperienceReviewJob(ExperienceReviewService experienceReviewService) {
        this.experienceReviewService = experienceReviewService;
    }

    public String run() {
        ExperienceReviewResult result = experienceReviewService.reviewNow();
        logger.info("Experience review completed: report={}, workflows={}, pendingCandidates={}",
                result.reportPath(),
                result.workflowCount(),
                result.pendingCandidateCount());
        return "Experience review completed: " + result.reportPath();
    }
}

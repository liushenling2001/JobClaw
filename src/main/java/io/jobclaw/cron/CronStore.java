package io.jobclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Cron 任务存储
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronStore {
    
    private List<CronJob> jobs = new ArrayList<>();
    
    public List<CronJob> getJobs() {
        return jobs;
    }
    
    public void setJobs(List<CronJob> jobs) {
        this.jobs = jobs;
    }
}

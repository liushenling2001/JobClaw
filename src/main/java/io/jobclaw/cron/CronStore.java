package io.jobclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Cron 任务存储
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CronStore {

    public static final int CURRENT_VERSION = 2;

    private int version = CURRENT_VERSION;
    private List<CronJob> jobs = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<CronJob> getJobs() {
        return jobs;
    }

    public void setJobs(List<CronJob> jobs) {
        this.jobs = jobs;
    }
}

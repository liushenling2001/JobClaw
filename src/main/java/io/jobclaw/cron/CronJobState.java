package io.jobclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cron 任务状态
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJobState {
    
    private Long lastRunAtMs;      // 最后执行时间
    private Long nextRunAtMs;      // 下次执行时间
    private String lastStatus;     // 最后执行状态
    private String lastError;      // 最后错误信息
    
    public Long getLastRunAtMs() {
        return lastRunAtMs;
    }
    
    public void setLastRunAtMs(Long lastRunAtMs) {
        this.lastRunAtMs = lastRunAtMs;
    }
    
    public Long getNextRunAtMs() {
        return nextRunAtMs;
    }
    
    public void setNextRunAtMs(Long nextRunAtMs) {
        this.nextRunAtMs = nextRunAtMs;
    }
    
    public String getLastStatus() {
        return lastStatus;
    }
    
    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}

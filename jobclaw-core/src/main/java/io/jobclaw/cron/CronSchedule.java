package io.jobclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cron 调度配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronSchedule {
    
    public enum ScheduleKind {
        AT,      // 一次性任务
        EVERY,   // 周期性任务
        CRON     // Cron 表达式任务
    }
    
    private ScheduleKind kind;
    private Long atMs;          // AT 类型：执行时间戳
    private Long everyMs;       // EVERY 类型：间隔毫秒
    private String expr;        // CRON 类型：Cron 表达式
    
    public CronSchedule() {
    }
    
    public static CronSchedule at(long atMs) {
        CronSchedule schedule = new CronSchedule();
        schedule.setKind(ScheduleKind.AT);
        schedule.setAtMs(atMs);
        return schedule;
    }
    
    public static CronSchedule every(long everyMs) {
        CronSchedule schedule = new CronSchedule();
        schedule.setKind(ScheduleKind.EVERY);
        schedule.setEveryMs(everyMs);
        return schedule;
    }
    
    public static CronSchedule cron(String expr) {
        CronSchedule schedule = new CronSchedule();
        schedule.setKind(ScheduleKind.CRON);
        schedule.setExpr(expr);
        return schedule;
    }
    
    public ScheduleKind getKind() {
        return kind;
    }
    
    public void setKind(ScheduleKind kind) {
        this.kind = kind;
    }
    
    public Long getAtMs() {
        return atMs;
    }
    
    public void setAtMs(Long atMs) {
        this.atMs = atMs;
    }
    
    public Long getEveryMs() {
        return everyMs;
    }
    
    public void setEveryMs(Long everyMs) {
        this.everyMs = everyMs;
    }
    
    public String getExpr() {
        return expr;
    }
    
    public void setExpr(String expr) {
        this.expr = expr;
    }
}

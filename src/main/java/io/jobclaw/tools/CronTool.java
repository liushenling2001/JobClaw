package io.jobclaw.tools;

import io.jobclaw.cron.CronJob;
import io.jobclaw.cron.CronSchedule;
import io.jobclaw.cron.CronService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时任务工具
 * 
 * 允许 Agent 创建、管理和执行定时任务
 * 
 * 支持的操作：
 * - add: 添加新任务
 * - list: 列出所有任务
 * - remove: 删除任务
 * - enable: 启用任务
 * - disable: 禁用任务
 * 
 * 支持的调度类型：
 * - at_seconds: 一次性提醒（多少秒后）
 * - every_seconds: 周期性任务（每隔多少秒）
 * - cron_expr: Cron 表达式（复杂调度）
 */
@Component
public class CronTool {

    private final CronService cronService;

    public CronTool(CronService cronService) {
        this.cronService = cronService;
    }

    @Tool(name = "cron", description = "Schedule reminders and tasks. IMPORTANT: Call this when user requests reminders or scheduled tasks. Use 'at_seconds' for one-time reminders (e.g., 'remind me in 10 minutes' → at_seconds=600). Use 'every_seconds' for recurring tasks (e.g., 'every 2 hours' → every_seconds=7200). Use 'cron_expr' for complex schedules (e.g., '0 9 * * *' for daily at 9 AM).")
    public String cron(
        @ToolParam(description = "Operation to perform: add, list, remove, enable, disable") String action,
        @ToolParam(description = "Reminder/task message to display when triggered (required for 'add')") String message,
        @ToolParam(description = "One-time reminder: seconds from now (e.g., 600 for 10 minutes)") Integer at_seconds,
        @ToolParam(description = "Recurring interval in seconds (e.g., 3600 for hourly)") Integer every_seconds,
        @ToolParam(description = "Cron expression for complex schedules (e.g., '0 9 * * *')") String cron_expr,
        @ToolParam(description = "Job ID (required for remove/enable/disable operations)") String job_id
    ) {
        if (action == null || action.isEmpty()) {
            return "Error: action parameter is required";
        }

        return switch (action) {
            case "add" -> addJob(message, at_seconds, every_seconds, cron_expr);
            case "list" -> listJobs();
            case "remove" -> removeJob(job_id);
            case "enable" -> enableJob(job_id, true);
            case "disable" -> enableJob(job_id, false);
            default -> "Error: Unknown action: " + action;
        };
    }

    private String addJob(String message, Integer atSeconds, Integer everySeconds, String cronExpr) {
        if (message == null || message.isEmpty()) {
            return "Error: message parameter is required for 'add' action";
        }

        // Validate schedule parameters
        if (atSeconds == null && everySeconds == null && (cronExpr == null || cronExpr.isEmpty())) {
            return "Error: Must provide one of: at_seconds, every_seconds, or cron_expr";
        }

        try {
            String messagePreview = message.length() > 30 ? message.substring(0, 30) + "..." : message;
            CronSchedule schedule;

            if (atSeconds != null) {
                long atMs = System.currentTimeMillis() + atSeconds * 1000L;
                schedule = CronSchedule.at(atMs);
            } else if (everySeconds != null) {
                long everyMs = everySeconds * 1000L;
                schedule = CronSchedule.every(everyMs);
            } else {
                schedule = CronSchedule.cron(cronExpr);
            }

            CronJob job = cronService.addJob(messagePreview, schedule, message, "feishu", "direct");

            String scheduleInfo = getScheduleInfo(atSeconds, everySeconds, cronExpr);

            return "✅ Created job '" + messagePreview + "' (id: " + job.getId() + ", schedule: " + scheduleInfo + ")\n\n" +
                   "The job will execute and deliver the message to the current channel.";
        } catch (Exception e) {
            return "Error adding job: " + e.getMessage();
        }
    }

    private String getScheduleInfo(Integer atSeconds, Integer everySeconds, String cronExpr) {
        if (atSeconds != null) {
            return "one-time in " + atSeconds + "s";
        } else if (everySeconds != null) {
            return "every " + everySeconds + "s";
        } else if (cronExpr != null) {
            return "cron: " + cronExpr;
        }
        return "unknown";
    }

    private String listJobs() {
        try {
            List<CronJob> jobs = cronService.listJobs(false);

            if (jobs.isEmpty()) {
                return "No scheduled jobs.";
            }

            StringBuilder result = new StringBuilder("📅 Scheduled jobs:\n\n");
            for (CronJob job : jobs) {
                String scheduleInfo = formatScheduleInfo(job.getSchedule());
                result.append("- **").append(job.getName()).append("** ")
                      .append("(id: `").append(job.getId()).append("`, ")
                      .append(scheduleInfo).append(")\n");
            }
            return result.toString();
        } catch (Exception e) {
            return "Error listing jobs: " + e.getMessage();
        }
    }

    private String formatScheduleInfo(CronSchedule schedule) {
        return switch (schedule.getKind()) {
            case EVERY -> schedule.getEveryMs() != null 
                    ? "every " + (schedule.getEveryMs() / 1000) + "s"
                    : "unknown";
            case CRON -> "cron: " + schedule.getExpr();
            case AT -> "one-time";
            default -> "unknown";
        };
    }

    private String removeJob(String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for 'remove' action";
        }

        try {
            return cronService.removeJob(jobId) 
                    ? "✅ Removed job " + jobId
                    : "Job " + jobId + " not found";
        } catch (Exception e) {
            return "Error removing job: " + e.getMessage();
        }
    }

    private String enableJob(String jobId, boolean enable) {
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for 'enable/disable' action";
        }

        try {
            CronJob job = cronService.enableJob(jobId, enable);
            if (job == null) {
                return "Job " + jobId + " not found";
            }

            String status = enable ? "enabled" : "disabled";
            return "✅ Job '" + job.getName() + "' " + status;
        } catch (Exception e) {
            return "Error enabling/disabling job: " + e.getMessage();
        }
    }

}

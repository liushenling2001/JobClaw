package io.jobclaw.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 定时任务管理工具
 * 
 * 允许 Agent 创建、管理和执行定时任务
 */
@Component
public class CronTool {

    // TODO: Inject actual CronService when available
    private final Map<String, CronJob> jobs = new HashMap<>();

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

        String jobId = "job_" + System.currentTimeMillis();
        String messagePreview = message.length() > 30 ? message.substring(0, 30) + "..." : message;

        // TODO: Integrate with actual cron service
        // For now, store in memory and return placeholder
        jobs.put(jobId, new CronJob(jobId, messagePreview, message));

        String scheduleInfo = getScheduleInfo(atSeconds, everySeconds, cronExpr);

        return "Created job '" + messagePreview + "' (id: " + jobId + ", schedule: " + scheduleInfo + ")\n\n" +
               "Note: Cron service integration pending. Job stored in memory only.";
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
        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }

        StringBuilder result = new StringBuilder("Scheduled jobs:\n");
        for (CronJob job : jobs.values()) {
            result.append("- ").append(job.name)
                  .append(" (id: ").append(job.id).append(")\n");
        }
        return result.toString();
    }

    private String removeJob(String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for 'remove' action";
        }

        if (jobs.remove(jobId) != null) {
            return "Removed job " + jobId;
        }
        return "Job " + jobId + " not found";
    }

    private String enableJob(String jobId, boolean enable) {
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for 'enable/disable' action";
        }

        CronJob job = jobs.get(jobId);
        if (job == null) {
            return "Job " + jobId + " not found";
        }

        String status = enable ? "enabled" : "disabled";
        return "Job '" + job.name + "' " + status;
    }

    private static class CronJob {
        String id;
        String name;
        String message;

        CronJob(String id, String name, String message) {
            this.id = id;
            this.name = name;
            this.message = message;
        }
    }
}

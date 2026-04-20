package io.jobclaw.agent;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.planning.TaskPlan;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.agent.planning.TaskPlanningPolicy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Selects a smaller task-specific toolset so the model does not see every tool
 * on every turn. Explicit agent tool allowlists are handled by AgentLoop and
 * intentionally bypass this policy.
 */
public class ToolSelectionPolicy {

    private static final Set<String> BASE_TOOLS = Set.of("memory", "skills", "context_ref");
    private static final Set<String> FILE_READ_TOOLS = Set.of(
            "list_dir", "read_file", "read_pdf", "read_word", "read_excel"
    );
    private static final Set<String> FILE_WRITE_TOOLS = Set.of(
            "write_file", "edit_file", "append_file"
    );
    private static final Set<String> EXECUTION_TOOLS = Set.of("run_command", "exec");
    private static final Set<String> WEB_TOOLS = Set.of("web_search", "web_fetch");
    private static final Set<String> WORKLIST_TOOLS = Set.of("subtasks", "spawn");
    private static final Set<String> AGENT_TOOLS = Set.of("agent_catalog", "spawn", "collaborate");
    private static final Set<String> BOARD_TOOLS = Set.of("board_write", "board_read");

    private final TaskPlanningPolicy planningPolicy;

    public ToolSelectionPolicy() {
        this(new TaskPlanningPolicy());
    }

    ToolSelectionPolicy(TaskPlanningPolicy planningPolicy) {
        this.planningPolicy = planningPolicy;
    }

    public Set<String> selectToolNames(String taskInput, Collection<String> availableToolNames) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        Set<String> available = new LinkedHashSet<>(availableToolNames != null ? availableToolNames : Set.of());
        String normalized = normalize(taskInput);

        addIfAvailable(selected, available, BASE_TOOLS);

        TaskPlan plan = planningPolicy.decide(taskInput);
        if (plan.planningMode() == TaskPlanningMode.WORKLIST
                || plan.doneDefinition().deliveryType() == DeliveryType.BATCH_RESULTS) {
            addIfAvailable(selected, available, FILE_READ_TOOLS);
            addIfAvailable(selected, available, WORKLIST_TOOLS);
        }

        switch (plan.doneDefinition().deliveryType()) {
            case DOCUMENT_SUMMARY -> addIfAvailable(selected, available, FILE_READ_TOOLS);
            case FILE_ARTIFACT -> {
                addIfAvailable(selected, available, FILE_READ_TOOLS);
                addIfAvailable(selected, available, FILE_WRITE_TOOLS);
                addIfAvailable(selected, available, EXECUTION_TOOLS);
            }
            case PATCH -> {
                addIfAvailable(selected, available, FILE_READ_TOOLS);
                addIfAvailable(selected, available, FILE_WRITE_TOOLS);
                addIfAvailable(selected, available, EXECUTION_TOOLS);
            }
            case BATCH_RESULTS -> {
                addIfAvailable(selected, available, FILE_READ_TOOLS);
                addIfAvailable(selected, available, WORKLIST_TOOLS);
            }
            case ANSWER -> {
                if (looksLikeFileTask(normalized)) {
                    addIfAvailable(selected, available, FILE_READ_TOOLS);
                }
            }
        }

        if (looksLikeWebTask(normalized)) {
            addIfAvailable(selected, available, WEB_TOOLS);
        }
        if (looksLikeMemoryTask(normalized)) {
            addIfAvailable(selected, available, "memory");
        }
        if (looksLikeSkillTask(normalized)) {
            addIfAvailable(selected, available, "skills");
            addIfAvailable(selected, available, EXECUTION_TOOLS);
        }
        if (looksLikeAgentTask(normalized)) {
            addIfAvailable(selected, available, AGENT_TOOLS);
        }
        if (looksLikeScheduleTask(normalized)) {
            addIfAvailable(selected, available, "cron");
        }
        if (looksLikeOutboundMessageTask(normalized)) {
            addIfAvailable(selected, available, "message");
        }
        if (looksLikeUsageTask(normalized)) {
            addIfAvailable(selected, available, "query_token_usage");
        }
        if (looksLikeMcpTask(normalized)) {
            addIfAvailable(selected, available, "mcp");
        }
        if (looksLikeCollaborationTask(normalized)) {
            addIfAvailable(selected, available, AGENT_TOOLS);
            addIfAvailable(selected, available, BOARD_TOOLS);
        }

        if (selected.isEmpty()) {
            addIfAvailable(selected, available, BASE_TOOLS);
        }
        return selected;
    }

    private boolean looksLikeFileTask(String text) {
        return containsAny(text,
                "文件", "目录", "文件夹", "路径", "读取", "查看", "保存", "写入",
                ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".txt", ".md", ".java", ".js", ".ts",
                "file", "folder", "directory", "path", "read", "write", "save");
    }

    private boolean looksLikeWebTask(String text) {
        return containsAny(text,
                "搜索", "联网", "网页", "网址", "链接", "最新", "新闻", "官网", "http://", "https://",
                "search", "web", "url", "latest", "news");
    }

    private boolean looksLikeMemoryTask(String text) {
        return containsAny(text,
                "记住", "记忆", "以后都", "偏好", "忘记", "长期", "remember", "memory", "forget", "preference");
    }

    private boolean looksLikeSkillTask(String text) {
        return containsAny(text,
                "技能", "skill", "安装技能", "创建技能", "调用技能");
    }

    private boolean looksLikeAgentTask(String text) {
        return containsAny(text,
                "智能体", "子智能体", "agent", "角色智能体", "agent配置", "agent catalog");
    }

    private boolean looksLikeScheduleTask(String text) {
        return containsAny(text,
                "提醒", "定时", "计划任务", "每天", "每周", "cron", "schedule", "remind");
    }

    private boolean looksLikeOutboundMessageTask(String text) {
        return containsAny(text,
                "发送消息", "发消息", "通知", "飞书", "钉钉", "telegram", "discord", "send message", "notify");
    }

    private boolean looksLikeUsageTask(String text) {
        return containsAny(text,
                "token", "用量", "费用", "消耗", "usage", "cost");
    }

    private boolean looksLikeMcpTask(String text) {
        return containsAny(text, "mcp", "model context protocol");
    }

    private boolean looksLikeCollaborationTask(String text) {
        return containsAny(text,
                "协作", "多智能体", "团队", "辩论", "collaborate", "debate", "team");
    }

    private void addIfAvailable(Set<String> selected, Set<String> available, Set<String> toolNames) {
        for (String toolName : toolNames) {
            addIfAvailable(selected, available, toolName);
        }
    }

    private void addIfAvailable(Set<String> selected, Set<String> available, String toolName) {
        if (available.contains(toolName)) {
            selected.add(toolName);
        }
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

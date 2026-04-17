package io.jobclaw.agent.planning;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class TaskPlanningPolicy {

    public TaskPlanningDecision decide(String taskInput) {
        String normalized = normalize(taskInput);
        if (normalized.isEmpty()) {
            return new TaskPlanningDecision(TaskPlanningMode.DIRECT, "empty_input");
        }

        if (looksLikeWorklistTask(normalized)) {
            return new TaskPlanningDecision(TaskPlanningMode.WORKLIST, "independent_batch_items");
        }
        if (looksLikePhasedTask(normalized)) {
            return new TaskPlanningDecision(TaskPlanningMode.PHASED, "multi_step_delivery_task");
        }
        return new TaskPlanningDecision(TaskPlanningMode.DIRECT, "direct_task");
    }

    private boolean looksLikeWorklistTask(String text) {
        boolean mentionsBatch = containsAny(text,
                "批量", "逐个", "逐一", "每个", "全部", "所有", "目录", "文件夹", "遍历", "多个文件",
                "for each", "each file", "all files", "batch", "directory", "folder");
        boolean mentionsIndependentItems = containsAny(text,
                ".pdf", ".doc", ".docx", ".xlsx", ".xls", "pdf文件", "word文件", "excel文件",
                "链接", "记录", "条数据", "文件");
        return mentionsBatch && mentionsIndependentItems;
    }

    private boolean looksLikePhasedTask(String text) {
        boolean mentionsFinalArtifact = containsAny(text,
                "撰写报告", "写报告", "形成报告", "报告", "总结", "汇总", "方案", "分析后", "根据数据",
                "write a report", "report", "analyze", "summarize", "based on data");
        boolean mentionsSequence = containsAny(text,
                "先", "然后", "再", "最后", "步骤", "分阶段", "整理后", "分析后",
                "first", "then", "next", "finally", "step");
        return mentionsFinalArtifact && mentionsSequence;
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

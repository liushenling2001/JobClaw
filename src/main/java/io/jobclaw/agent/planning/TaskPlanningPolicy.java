package io.jobclaw.agent.planning;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class TaskPlanningPolicy {

    public TaskPlan decide(String taskInput) {
        String normalized = normalize(taskInput);
        if (normalized.isEmpty()) {
            return new TaskPlan(
                    TaskPlanningMode.DIRECT,
                    doneDefinition(TaskPlanningMode.DIRECT, DeliveryType.ANSWER),
                    "empty_input"
            );
        }

        if (looksLikeWorklistTask(normalized)) {
            return new TaskPlan(
                    TaskPlanningMode.WORKLIST,
                    doneDefinition(TaskPlanningMode.WORKLIST, DeliveryType.BATCH_RESULTS),
                    "independent_batch_items"
            );
        }
        if (looksLikePhasedTask(normalized)) {
            DeliveryType deliveryType = looksLikeReportTask(normalized)
                    ? DeliveryType.FILE_ARTIFACT
                    : (looksLikePatchTask(normalized) ? DeliveryType.PATCH : DeliveryType.ANSWER);
            return new TaskPlan(
                    TaskPlanningMode.PHASED,
                    doneDefinition(TaskPlanningMode.PHASED, deliveryType),
                    "multi_step_delivery_task"
            );
        }
        DeliveryType deliveryType = looksLikeDocumentSummaryTask(normalized)
                ? DeliveryType.DOCUMENT_SUMMARY
                : (looksLikeReportTask(normalized)
                ? DeliveryType.FILE_ARTIFACT
                : (looksLikePatchTask(normalized) ? DeliveryType.PATCH : DeliveryType.ANSWER));
        return new TaskPlan(
                TaskPlanningMode.DIRECT,
                doneDefinition(TaskPlanningMode.DIRECT, deliveryType),
                "direct_task"
        );
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

    private boolean looksLikeReportTask(String text) {
        return containsAny(text,
                "撰写报告", "写报告", "形成报告", "生成报告", "创建报告", "输出报告",
                "write a report", "create report", "generate report");
    }

    private boolean looksLikeDocumentSummaryTask(String text) {
        boolean summaryTask = containsAny(text,
                "总结", "概括", "提炼", "归纳", "概述", "分析",
                "summarize", "summary", "analyse", "analyze");
        boolean documentTask = containsAny(text,
                ".pdf", ".doc", ".docx", ".xlsx", ".xls", "pdf", "word", "excel", "文件", "文档", "材料", "附件");
        return summaryTask && documentTask;
    }

    private boolean looksLikePatchTask(String text) {
        return containsAny(text,
                "修改代码", "修改文件", "编辑文件", "更新文件", "修复代码", "补丁", "改一下",
                "edit file", "modify file", "update file", "patch", "fix code", "change code");
    }

    private DoneDefinition doneDefinition(TaskPlanningMode planningMode, DeliveryType deliveryType) {
        return switch (deliveryType) {
            case BATCH_RESULTS -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    List.of(TaskHarnessPhase.PLAN, TaskHarnessPhase.ACT),
                    true,
                    true,
                    false,
                    List.of("worklist_planned", "pending_subtasks_zero", "final_summary")
            );
            case FILE_ARTIFACT -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    List.of(TaskHarnessPhase.ACT),
                    false,
                    false,
                    true,
                    List.of("artifact_exists")
            );
            case DOCUMENT_SUMMARY -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    List.of(TaskHarnessPhase.ACT),
                    false,
                    true,
                    true,
                    List.of("document_summary")
            );
            case PATCH -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    List.of(TaskHarnessPhase.ACT),
                    false,
                    false,
                    true,
                    List.of("file_change")
            );
            case ANSWER -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    List.of(),
                    false,
                    true,
                    true,
                    List.of("usable_answer")
            );
        };
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

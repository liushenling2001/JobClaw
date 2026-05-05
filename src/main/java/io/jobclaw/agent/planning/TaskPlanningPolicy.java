package io.jobclaw.agent.planning;

import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskPlanningPolicy {

    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("(?i)([A-Z]:\\\\[^\\r\\n\"'<>|]+)");

    public TaskPlan decide(String taskInput) {
        String normalized = normalize(taskInput);
        if (normalized.isEmpty()) {
            return new TaskPlan(
                    TaskPlanningMode.DIRECT,
                    doneDefinition(TaskPlanningMode.DIRECT, DeliveryType.ANSWER, normalized),
                    "empty_input"
            );
        }

        if (looksLikeSourceToFileArtifactTask(normalized)) {
            return new TaskPlan(
                    TaskPlanningMode.PHASED,
                    doneDefinition(TaskPlanningMode.PHASED, DeliveryType.FILE_ARTIFACT, taskInput),
                    "source_to_file_artifact_task",
                    phasedSteps(DeliveryType.FILE_ARTIFACT)
            );
        }
        if (looksLikePhasedTask(normalized) || looksLikeWorklistTask(normalized)) {
            DeliveryType deliveryType = looksLikeReportTask(normalized)
                    ? DeliveryType.FILE_ARTIFACT
                    : ((looksLikePatchTask(normalized) || looksLikeSourceSynthesisTask(normalized))
                    ? DeliveryType.PATCH
                    : (looksLikeDocumentSummaryTask(normalized) ? DeliveryType.DOCUMENT_SUMMARY : DeliveryType.ANSWER));
            return new TaskPlan(
                    TaskPlanningMode.PHASED,
                    doneDefinition(TaskPlanningMode.PHASED, deliveryType, taskInput),
                    "multi_step_delivery_task",
                    phasedSteps(deliveryType)
            );
        }
        DeliveryType deliveryType = looksLikeDocumentSummaryTask(normalized)
                ? DeliveryType.DOCUMENT_SUMMARY
                : (looksLikeReportTask(normalized)
                ? DeliveryType.FILE_ARTIFACT
                : (looksLikePatchTask(normalized) ? DeliveryType.PATCH : DeliveryType.ANSWER));
        if (deliveryType != DeliveryType.ANSWER) {
            return new TaskPlan(
                    TaskPlanningMode.PHASED,
                    doneDefinition(TaskPlanningMode.PHASED, deliveryType, taskInput),
                    "tool_or_artifact_task",
                    phasedSteps(deliveryType)
            );
        }
        return new TaskPlan(
                TaskPlanningMode.DIRECT,
                doneDefinition(TaskPlanningMode.DIRECT, deliveryType, taskInput),
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
        return (mentionsFinalArtifact && mentionsSequence) || looksLikeSourceSynthesisTask(text);
    }

    private boolean looksLikeReportTask(String text) {
        return containsAny(text,
                "撰写报告", "写报告", "形成报告", "生成报告", "创建报告", "输出报告",
                "撰写综述", "写综述", "生成综述", "创建综述", "输出综述",
                "写一个综述", "综述word", "综述文档", "word文档", "docx文档",
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
                "修改代码", "修改文件", "编辑文件", "更新文件", "完善文件", "完善1个文件", "完善一个文件",
                "修复代码", "补丁", "改一下",
                "edit file", "modify file", "update file", "patch", "fix code", "change code");
    }

    private boolean looksLikeSourceSynthesisTask(String text) {
        boolean sourceBased = containsAny(text,
                "根据多个", "多个其他", "多个参考", "参考文件", "参考材料", "其他文件", "若干文件",
                "based on multiple", "reference files", "source files");
        boolean targetMutation = containsAny(text,
                "完善", "补充", "修改", "更新", "形成", "生成", "撰写", "写入",
                "improve", "update", "revise", "complete", "write");
        boolean fileMentioned = containsAny(text,
                "文件", "文档", ".doc", ".docx", ".pdf", ".xls", ".xlsx", ".md", ".txt",
                "file", "document");
        return sourceBased && targetMutation && fileMentioned;
    }

    private boolean looksLikeSourceToFileArtifactTask(String text) {
        boolean sourceSet = containsAny(text,
                "目录", "文件夹", "路径", "下的", "全部", "所有", "多个", "批量", "若干",
                "folder", "directory", "all files", "multiple");
        boolean finalArtifact = containsAny(text,
                "word", "docx", ".docx", ".doc", "报告", "综述", "文档", "文件",
                "report", "document");
        boolean produce = containsAny(text,
                "写", "撰写", "生成", "创建", "输出", "保存", "放在", "形成",
                "write", "create", "generate", "save", "produce");
        boolean sourceDocuments = containsAny(text,
                ".pdf", "pdf", ".doc", ".docx", ".xls", ".xlsx", "材料", "文献", "资料", "文档",
                "source", "reference");
        return sourceSet && finalArtifact && produce && sourceDocuments;
    }

    private List<PlanStep> phasedSteps(DeliveryType deliveryType) {
        if (deliveryType == DeliveryType.PATCH || deliveryType == DeliveryType.FILE_ARTIFACT) {
            return List.of(
                    new PlanStep("inspect-inputs", "确认输入材料和目标文件", "必要文件已定位，路径清晰"),
                    new PlanStep("process-sources", "读取并提炼参考材料", "参考材料形成简短可用要点，必要大内容只保留 context_ref"),
                    new PlanStep("read-target", "读取目标文件当前内容", "掌握目标文件当前结构和待完善位置"),
                    new PlanStep("update-target", "根据提炼结果更新目标文件", "目标文件已通过写入或编辑工具落地"),
                    new PlanStep("verify-output", "验证产物并给出简短完成说明", "确认目标文件存在且最终回复简洁")
            );
        }
        return List.of(
                new PlanStep("inspect-inputs", "确认任务输入和约束", "输入和约束已明确"),
                new PlanStep("process-sources", "处理必要资料或工具结果", "形成可用于回答的简短依据"),
                new PlanStep("produce-answer", "输出最终结果", "给出满足任务要求的最终答复")
        );
    }

    private DoneDefinition doneDefinition(TaskPlanningMode planningMode, DeliveryType deliveryType, String taskInput) {
        List<String> artifactDirectories = deliveryType == DeliveryType.FILE_ARTIFACT || deliveryType == DeliveryType.PATCH
                ? extractLikelyDirectories(taskInput)
                : List.of();
        return switch (deliveryType) {
            case BATCH_RESULTS -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    List.of(),
                    List.of(TaskHarnessPhase.PLAN, TaskHarnessPhase.ACT),
                    false,
                    true,
                    false,
                    List.of("worklist_planned", "pending_subtasks_zero", "final_summary")
            );
            case FILE_ARTIFACT -> new DoneDefinition(
                    planningMode,
                    deliveryType,
                    List.of(),
                    artifactDirectories,
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
                    artifactDirectories,
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
                    List.of(),
                    false,
                    true,
                    true,
                    List.of("usable_answer")
            );
        };
    }

    private List<String> extractLikelyDirectories(String taskInput) {
        if (taskInput == null || taskInput.isBlank()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = WINDOWS_PATH_PATTERN.matcher(taskInput);
        while (matcher.find()) {
            String candidate = cleanDirectoryCandidate(matcher.group(1));
            if (!candidate.matches("(?i).+\\.(pdf|docx?|xlsx?|txt|md|pptx?)$")) {
                paths.add(candidate);
            }
        }
        return List.copyOf(paths);
    }

    private String cleanDirectoryCandidate(String value) {
        String cleaned = value == null ? "" : value.trim();
        String[] stops = {"”", "“", "\"", "'", "`", "\r", "\n", " 目录", "目录", " 文件夹", "文件夹",
                " 下", "下的", "下面", " 中", "里", "，", ",", "。", "；", ";"};
        for (String stop : stops) {
            int index = cleaned.indexOf(stop);
            if (index >= 0) {
                cleaned = cleaned.substring(0, index).trim();
            }
        }
        while (cleaned.endsWith("\\") || cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
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

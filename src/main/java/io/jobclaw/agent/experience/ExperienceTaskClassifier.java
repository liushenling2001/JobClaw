package io.jobclaw.agent.experience;

import java.util.Locale;

final class ExperienceTaskClassifier {

    private ExperienceTaskClassifier() {
    }

    static TaskSignature classify(String text) {
        String value = text != null ? text.toLowerCase(Locale.ROOT) : "";
        String taskPattern = "unknown";
        String objectType = "unknown";

        if (containsAny(value, "清理", "删除", "delete", "remove", "clean")) {
            taskPattern = "cleanup";
            objectType = containsAny(value, "文件夹", "目录", "folder", "directory", "dir") ? "folder" : "unknown";
        } else if (containsAny(value, "提取", "抽取", "extract")) {
            taskPattern = "extract";
            objectType = containsAny(value, "excel", "xlsx", "表格", "spreadsheet") ? "spreadsheet"
                    : containsAny(value, "文档", "论文", "pdf", "docx", "document") ? "document" : "unknown";
        } else if (containsAny(value, "迁移", "升级", "upgrade", "migrate")) {
            taskPattern = "migration";
            objectType = containsAny(value, "代码", "项目", "repo", "repository", "spring", "java") ? "codebase" : "unknown";
        } else if (containsAny(value, "issue", "github", "pr", "pull request")) {
            taskPattern = "github";
            objectType = containsAny(value, "issue") ? "issue" : containsAny(value, "pr", "pull request") ? "pull_request" : "repo";
        } else if (containsAny(value, "word", "docx", "综述", "报告", "document", "report")) {
            taskPattern = "document_generation";
            objectType = "document";
        }

        return new TaskSignature(taskPattern, objectType);
    }

    static boolean compatible(TaskSignature current, TaskSignature memory) {
        if (current == null || memory == null) {
            return false;
        }
        if ("unknown".equals(current.taskPattern()) || "unknown".equals(memory.taskPattern())) {
            return false;
        }
        if (!current.taskPattern().equals(memory.taskPattern())) {
            return false;
        }
        return "unknown".equals(memory.objectType())
                || "unknown".equals(current.objectType())
                || current.objectType().equals(memory.objectType());
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    record TaskSignature(String taskPattern, String objectType) {
    }
}

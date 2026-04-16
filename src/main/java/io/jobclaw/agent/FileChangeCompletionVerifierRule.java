package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@Order(290)
public class FileChangeCompletionVerifierRule implements TaskHarnessVerifierRule {

    private static final Set<String> MUTATING_FILE_TOOLS = Set.of("write_file", "edit_file", "append_file");

    private static final List<String> FILE_CHANGE_TASK_HINTS = List.of(
            "写",
            "修改",
            "编辑",
            "更新",
            "生成",
            "创建",
            "追加",
            "write",
            "edit",
            "update",
            "modify",
            "create",
            "append"
    );

    private static final List<String> COMPLETION_MARKERS = List.of(
            "已完成",
            "已经完成",
            "完成了",
            "处理完成",
            "修改完成",
            "已修改",
            "已更新",
            "已写入",
            "已创建",
            "successfully",
            "updated",
            "edited",
            "written",
            "created"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null || !isFileChangeContext(run)) {
            return TaskHarnessVerificationResult.ok("File change verifier skipped");
        }

        if (finalResponse == null || finalResponse.isBlank()) {
            return TaskHarnessVerificationResult.fail(
                    "FILE_CHANGE_INCOMPLETE",
                    "File change task ended without a usable completion response"
            );
        }

        String normalized = normalize(finalResponse);
        if (looksLikePlanning(normalized)) {
            return TaskHarnessVerificationResult.fail(
                    "FILE_CHANGE_INCOMPLETE",
                    "File change task still reads like future work"
            );
        }

        if (hasCompletionEvidence(finalResponse, normalized) || mentionsTouchedPath(run, normalized)) {
            return TaskHarnessVerificationResult.ok("File change response contains completion evidence");
        }

        if (normalized.length() >= 40) {
            return TaskHarnessVerificationResult.ok("File change response is substantive and not planning");
        }

        return TaskHarnessVerificationResult.fail(
                "FILE_CHANGE_INCOMPLETE",
                "File change task did not produce a clear completion response"
        );
    }

    private boolean isFileChangeContext(TaskHarnessRun run) {
        String taskInput = normalize(run.getTaskInput());
        boolean hinted = FILE_CHANGE_TASK_HINTS.stream().anyMatch(taskInput::contains);
        boolean toolUsed = run.getSteps().stream()
                .filter(step -> "TOOL_START".equals(step.metadata().get("eventType")))
                .map(step -> step.metadata().get("toolName"))
                .filter(value -> value != null)
                .map(value -> value.toString().toLowerCase(Locale.ROOT))
                .anyMatch(MUTATING_FILE_TOOLS::contains);
        return hinted && toolUsed;
    }

    private boolean hasCompletionEvidence(String original, String normalized) {
        if (COMPLETION_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return original.contains("```") || original.contains(".java") || original.contains(".md") || original.contains(".json");
    }

    private boolean mentionsTouchedPath(TaskHarnessRun run, String normalized) {
        return run.getSteps().stream()
                .filter(step -> "TOOL_START".equals(step.metadata().get("eventType")))
                .map(TaskHarnessStep::metadata)
                .map(metadata -> metadata.get("request"))
                .filter(value -> value != null)
                .map(Object::toString)
                .map(this::extractPathHint)
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private String extractPathHint(String request) {
        if (request == null || request.isBlank()) {
            return null;
        }
        int pathIndex = request.indexOf("\"path\"");
        if (pathIndex < 0) {
            return null;
        }
        int colonIndex = request.indexOf(':', pathIndex);
        int firstQuote = request.indexOf('"', colonIndex + 1);
        int secondQuote = request.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return null;
        }
        return request.substring(firstQuote + 1, secondQuote);
    }

    private boolean looksLikePlanning(String normalized) {
        return normalized.startsWith("我先")
                || normalized.contains("接下来")
                || normalized.contains("继续处理")
                || normalized.contains("继续修改")
                || normalized.contains("i will")
                || normalized.contains("next i will");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

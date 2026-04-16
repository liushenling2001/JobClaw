package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@Order(295)
public class PureAnswerCompletionVerifierRule implements TaskHarnessVerifierRule {

    private static final List<String> ANSWER_TASK_HINTS = List.of(
            "?",
            "？",
            "什么",
            "为啥",
            "为什么",
            "如何",
            "怎么",
            "解释",
            "说明",
            "介绍",
            "compare",
            "what",
            "why",
            "how",
            "explain",
            "summarize",
            "translate"
    );

    private static final List<String> PLANNING_MARKERS = List.of(
            "我先",
            "接下来",
            "下一步",
            "我会继续",
            "i will",
            "next i will",
            "let me first"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null || !isPureAnswerContext(run)) {
            return TaskHarnessVerificationResult.ok("Pure answer verifier skipped");
        }

        if (finalResponse == null || finalResponse.isBlank()) {
            return TaskHarnessVerificationResult.fail(
                    "PURE_ANSWER_INCOMPLETE",
                    "Pure answer task ended without a usable response"
            );
        }

        String normalized = normalize(finalResponse);
        if (PLANNING_MARKERS.stream().anyMatch(normalized::contains)) {
            return TaskHarnessVerificationResult.fail(
                    "PURE_ANSWER_INCOMPLETE",
                    "Pure answer task still reads like future work"
            );
        }

        if (isSubstantiveAnswer(finalResponse, normalized)) {
            return TaskHarnessVerificationResult.ok("Pure answer response looks complete");
        }

        return TaskHarnessVerificationResult.fail(
                "PURE_ANSWER_INCOMPLETE",
                "Pure answer task did not produce a substantive answer"
        );
    }

    private boolean isPureAnswerContext(TaskHarnessRun run) {
        boolean hasToolActivity = run.getSteps().stream()
                .anyMatch(step -> "TOOL_START".equals(step.metadata().get("eventType")));
        if (hasToolActivity) {
            return false;
        }

        String taskInput = normalize(run.getTaskInput());
        return ANSWER_TASK_HINTS.stream().anyMatch(taskInput::contains);
    }

    private boolean isSubstantiveAnswer(String original, String normalized) {
        if (original.split("\\R").length >= 2) {
            return true;
        }
        if (normalized.length() >= 40) {
            return true;
        }
        return normalized.contains("因为")
                || normalized.contains("所以")
                || normalized.contains("原因")
                || normalized.contains("例如")
                || normalized.contains("because")
                || normalized.contains("for example");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@Order(90)
public class PlanningResponseTaskHarnessVerifierRule implements TaskHarnessVerifierRule {

    private static final List<String> PLANNING_PREFIXES = List.of(
            "我准备用",
            "我准备使用",
            "我将使用",
            "我会使用",
            "我先",
            "我先用",
            "我先去",
            "接下来我会",
            "接下来我将",
            "下一步我会",
            "下一步我将",
            "后续我会",
            "后续我将",
            "还需要",
            "还要继续",
            "我还需要",
            "我还要",
            "让我先",
            "i will use",
            "i'm going to use",
            "i am going to use",
            "i'll use",
            "next, i will",
            "next i will",
            "i still need to",
            "i need to continue",
            "let me first",
            "first, i will",
            "first i will"
    );

    private static final List<String> PLANNING_CONTAINS = List.of(
            "准备使用",
            "准备用",
            "将使用",
            "会使用",
            "先分析",
            "先查看",
            "先读取",
            "先检查",
            "先整理",
            "先确认",
            "然后再",
            "接下来",
            "下一步",
            "后续",
            "还需要",
            "还要",
            "继续处理",
            "继续分析",
            "继续查看",
            "继续完成",
            "需要进一步",
            "稍后我会",
            "will use",
            "going to use",
            "still need to",
            "need to continue",
            "let me first",
            "next i will",
            "then i will"
    );

    private static final List<String> COMPLETION_SIGNALS = List.of(
            "分析结果",
            "结论",
            "摘要",
            "结果如下",
            "总结如下",
            "分析如下",
            "发现",
            "数据显示",
            "最终结果",
            "最终结论",
            "处理完成",
            "已经完成",
            "已完成",
            "完成了",
            "结论是",
            "结果是",
            "the result",
            "results:",
            "findings",
            "summary",
            "completed",
            "done"
    );

    private static final List<String> CONDITIONAL_CONTINUE_MARKERS = List.of(
            "如果你需要",
            "如果你愿意",
            "如需",
            "如果需要",
            "需要我继续",
            "需要我进一步",
            "需要我再",
            "要我继续",
            "要不要我继续",
            "是否需要我继续",
            "是否要我继续",
            "if you want",
            "if needed",
            "if you need",
            "would you like me to continue",
            "do you want me to continue"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null || finalResponse == null || finalResponse.isBlank()) {
            return TaskHarnessVerificationResult.ok("Planning response rule skipped");
        }

        String normalized = normalize(finalResponse);
        boolean planning = looksLikePlanningResponse(normalized);
        if (!planning) {
            return TaskHarnessVerificationResult.ok("Final response does not look like a planning-only reply");
        }

        String trailingSegment = trailingSegment(normalized);
        boolean trailingPlanning = looksLikePlanningResponse(trailingSegment);
        boolean completion = containsCompletionSignal(normalized);
        boolean toolActivity = hasToolActivity(run);
        boolean optionalFollowUpQuestion = isOptionalFollowUpQuestion(normalized, trailingSegment);

        if (completion && optionalFollowUpQuestion) {
            return TaskHarnessVerificationResult.ok("Response includes a completed result plus an optional follow-up question");
        }

        if (containsConditionalContinue(normalized)) {
            return TaskHarnessVerificationResult.ok("Planning language is conditional follow-up, not an unfinished task");
        }

        if (trailingPlanning) {
            return TaskHarnessVerificationResult.fail(
                    "INCOMPLETE_RESPONSE",
                    "Final response still ends in future work instead of a delivered result"
            );
        }

        if (toolActivity && !completion) {
            return TaskHarnessVerificationResult.fail(
                    "INCOMPLETE_RESPONSE",
                    "Tool activity happened, but the final response still reads like ongoing work"
            );
        }

        if (completion) {
            return TaskHarnessVerificationResult.ok("Planning language present, but response contains concrete completion signals");
        }

        return TaskHarnessVerificationResult.fail(
                "INCOMPLETE_RESPONSE",
                "Final response looks like a plan for future work instead of a completed result"
        );
    }

    private boolean looksLikePlanningResponse(String normalized) {
        for (String prefix : PLANNING_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        for (String marker : PLANNING_CONTAINS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCompletionSignal(String normalized) {
        for (String signal : COMPLETION_SIGNALS) {
            if (normalized.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsConditionalContinue(String normalized) {
        for (String marker : CONDITIONAL_CONTINUE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOptionalFollowUpQuestion(String normalized, String trailingSegment) {
        boolean asksQuestion = normalized.contains("?") || normalized.contains("？");
        return asksQuestion && containsConditionalContinue(trailingSegment);
    }

    private boolean hasToolActivity(TaskHarnessRun run) {
        return run.getSteps().stream()
                .anyMatch(step -> "TOOL_START".equals(step.metadata().get("eventType")));
    }

    private String trailingSegment(String normalized) {
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("[。！？!?.\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            return normalized;
        }
        return parts.get(parts.size() - 1);
    }

    private String normalize(String text) {
        return text
                .trim()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .toLowerCase(Locale.ROOT);
    }
}

package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
            "接下来我会",
            "接下来我将",
            "下一步我会",
            "下一步我将",
            "让我先",
            "i will use",
            "i'm going to use",
            "i am going to use",
            "i'll use",
            "next, i will",
            "next i will",
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
            "然后再",
            "接下来",
            "下一步",
            "稍后我会",
            "will use",
            "going to use",
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
            "处理完成",
            "已经完成",
            "已完成",
            "完成了",
            "the result",
            "results:",
            "findings",
            "summary",
            "completed",
            "done"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null || finalResponse == null || finalResponse.isBlank()) {
            return TaskHarnessVerificationResult.ok("Planning response rule skipped");
        }

        String normalized = normalize(finalResponse);
        if (!looksLikePlanningResponse(normalized)) {
            return TaskHarnessVerificationResult.ok("Final response does not look like a planning-only reply");
        }
        if (containsCompletionSignal(normalized)) {
            return TaskHarnessVerificationResult.ok("Planning language present, but response contains completion signals");
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

    private String normalize(String text) {
        return text
                .trim()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .toLowerCase(Locale.ROOT);
    }
}

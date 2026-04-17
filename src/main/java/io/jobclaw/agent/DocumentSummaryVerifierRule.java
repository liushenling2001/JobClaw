package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(280)
public class DocumentSummaryVerifierRule implements TaskHarnessVerifierRule {

    private static final Set<String> DOCUMENT_TOOLS = Set.of(
            "read_pdf",
            "read_word",
            "read_excel",
            "read_file"
    );

    private static final List<String> SUMMARY_VERBS = List.of(
            "总结",
            "概括",
            "提炼",
            "梳理",
            "归纳",
            "概述",
            "分析",
            "summarize",
            "summary",
            "summarise",
            "analyze",
            "analyse"
    );

    private static final List<String> DOCUMENT_HINTS = List.of(
            "pdf",
            "word",
            "excel",
            "docx",
            "doc",
            "xlsx",
            "xls",
            "文件",
            "文档",
            "材料",
            "报告",
            "附件"
    );

    private static final List<String> SUMMARY_MARKERS = List.of(
            "主要内容如下",
            "总结如下",
            "概括如下",
            "分析结果",
            "结论是",
            "结果如下",
            "要点如下",
            "主要包括",
            "概述如下",
            "summary:",
            "findings:",
            "key points"
    );

    private static final List<String> OPTIONAL_FOLLOW_UP_MARKERS = List.of(
            "需要我继续",
            "需要我进一步",
            "需要我再",
            "要我继续",
            "要不要我继续",
            "是否需要我继续",
            "是否要我继续",
            "如果你需要",
            "如需",
            "if you need",
            "if you want",
            "would you like me to continue",
            "do you want me to continue"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null || !isDocumentSummaryContext(run)) {
            return TaskHarnessVerificationResult.ok("Document summary verifier skipped");
        }

        if (finalResponse == null || finalResponse.isBlank()) {
            return TaskHarnessVerificationResult.fail(
                    "DOCUMENT_SUMMARY_INCOMPLETE",
                    "Document summary task ended without a usable summary"
            );
        }

        String normalized = normalize(finalResponse);
        boolean summaryEvidence = hasSummaryEvidence(finalResponse, normalized);
        boolean optionalFollowUp = isOptionalFollowUp(finalResponse, normalized);

        if (summaryEvidence) {
            return TaskHarnessVerificationResult.ok(
                    optionalFollowUp
                            ? "Document summary delivered with optional follow-up"
                            : "Document summary contains substantive result content"
            );
        }

        if (optionalFollowUp) {
            return TaskHarnessVerificationResult.fail(
                    "DOCUMENT_SUMMARY_INCOMPLETE",
                    "Response asks whether to continue, but did not yet deliver a substantive document summary"
            );
        }

        return TaskHarnessVerificationResult.fail(
                "DOCUMENT_SUMMARY_INCOMPLETE",
                "Document summary task did not produce a substantive summary"
        );
    }

    private boolean isDocumentSummaryContext(TaskHarnessRun run) {
        String taskInput = normalize(run.getTaskInput());
        boolean summaryTask = SUMMARY_VERBS.stream().anyMatch(taskInput::contains);
        boolean documentTask = DOCUMENT_HINTS.stream().anyMatch(taskInput::contains);
        boolean documentToolUsed = run.getSteps().stream()
                .filter(step -> "TOOL_START".equals(step.metadata().get("eventType")))
                .map(step -> step.metadata().get("toolName"))
                .filter(value -> value != null)
                .map(value -> value.toString().toLowerCase(Locale.ROOT))
                .anyMatch(DOCUMENT_TOOLS::contains);
        return documentToolUsed && (summaryTask || documentTask);
    }

    private boolean hasSummaryEvidence(String original, String normalized) {
        if (SUMMARY_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }

        int bulletLines = 0;
        int nonEmptyLines = 0;
        for (String line : original.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonEmptyLines++;
            if (trimmed.startsWith("-")
                    || trimmed.startsWith("*")
                    || trimmed.startsWith("•")
                    || trimmed.matches("^\\d+[.)、].*")
                    || trimmed.startsWith("###")
                    || trimmed.startsWith("##")) {
                bulletLines++;
            }
        }

        if (bulletLines >= 2 && nonEmptyLines >= 3) {
            return true;
        }

        return normalized.length() >= 120 && nonEmptyLines >= 3;
    }

    private boolean isOptionalFollowUp(String original, String normalized) {
        boolean asksQuestion = original.contains("?") || original.contains("？");
        return asksQuestion && OPTIONAL_FOLLOW_UP_MARKERS.stream().anyMatch(normalized::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

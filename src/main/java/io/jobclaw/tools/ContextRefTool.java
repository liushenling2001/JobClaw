package io.jobclaw.tools;

import io.jobclaw.agent.AgentExecutionContext;
import io.jobclaw.context.result.ContextRef;
import io.jobclaw.context.result.ResultStore;
import io.jobclaw.context.result.StoredResult;
import io.jobclaw.config.Config;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class ContextRefTool {
    private static final int DEFAULT_READ_MAX_CHARS = 12_000;
    private static final int DEFAULT_READ_TURN_BUDGET_CHARS = 45_000;
    private static final int BUDGET_EXCEEDED_SMALL_READ_CHARS = 2_000;
    private static final int DEFAULT_SEARCH_MAX_CHARS = 4_000;
    private static final int DEFAULT_LIST_LIMIT = 20;

    private final ResultStore resultStore;
    private final Config config;
    private final Map<String, AtomicInteger> readBudgetByRun = new ConcurrentHashMap<>();

    public ContextRefTool(ResultStore resultStore, Config config) {
        this.resultStore = resultStore;
        this.config = config;
    }

    @Tool(name = "context_ref", description = "Read, search, summarize, or list large tool and child-agent results saved as context references. Use this when a tool returns a refId instead of full content.")
    public String contextRef(
            @ToolParam(description = "Action: read, search, summary, or list") String action,
            @ToolParam(description = "Reference id returned by a previous tool, required for read/search/summary") String refId,
            @ToolParam(description = "Search query for action=search") String query,
            @ToolParam(description = "Start character offset for action=read, default 0") String start,
            @ToolParam(description = "Maximum characters to return for action=read, default agent.contextRefReadMaxChars") String maxChars,
            @ToolParam(description = "Maximum matches/list items, default 20") String limit
    ) {
        String normalizedAction = action == null || action.isBlank() ? "summary" : action.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedAction) {
            case "read" -> read(refId, parseInt(start, 0), parseInt(maxChars, readMaxChars()));
            case "search" -> search(refId, query, parseInt(limit, DEFAULT_LIST_LIMIT));
            case "list" -> list(parseInt(limit, DEFAULT_LIST_LIMIT));
            case "summary", "summarize" -> summary(refId);
            default -> "Error: unsupported context_ref action: " + action;
        };
    }

    private String read(String refId, int start, int maxChars) {
        Optional<StoredResult> result = resultStore.find(refId);
        if (result.isEmpty()) {
            return "Error: context reference not found: " + refId;
        }
        String content = result.get().getContent() != null ? result.get().getContent() : "";
        int safeStart = Math.max(0, Math.min(start, content.length()));
        int configuredMax = Math.max(1, readMaxChars());
        int requestedMax = Math.max(1, maxChars);
        int safeMax = Math.min(requestedMax, configuredMax);
        ReadBudgetDecision budget = reserveReadBudget(safeMax, requestedMax);
        if (!budget.allowed()) {
            return "Context reference read budget exceeded for the current run.\n\n"
                    + "refId: " + result.get().getRefId() + "\n"
                    + "requestedChars: " + requestedMax + "\n"
                    + "singleReadMaxChars: " + configuredMax + "\n"
                    + "turnReadBudgetChars: " + readTurnBudgetChars() + "\n"
                    + "usedBefore: " + budget.usedBefore() + "\n\n"
                    + "Use context_ref(action='search', refId='" + result.get().getRefId() + "', query='...') "
                    + "or context_ref(action='summary', refId='" + result.get().getRefId() + "') instead. "
                    + "If exact text is still needed, request a focused read with maxChars <= "
                    + BUDGET_EXCEEDED_SMALL_READ_CHARS + ".";
        }
        safeMax = budget.allowedChars();
        int end = Math.min(content.length(), safeStart + safeMax);
        String warning = requestedMax > configuredMax
                ? "Read clamped to configured single-read max of " + configuredMax + " chars.\n"
                : "";
        return warning
                + "Context reference: " + result.get().getRefId() + "\n"
                + "Source: " + nullSafe(result.get().getSourceName()) + "\n"
                + "Range: " + safeStart + "-" + end + " of " + content.length() + "\n\n"
                + content.substring(safeStart, end);
    }

    private String search(String refId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return "Error: query is required for context_ref search";
        }
        Optional<StoredResult> result = resultStore.find(refId);
        if (result.isEmpty()) {
            return "Error: context reference not found: " + refId;
        }
        String content = result.get().getContent() != null ? result.get().getContent() : "";
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for refId ").append(result.get().getRefId()).append(" query '").append(query).append("':\n");
        int count = 0;
        int from = 0;
        int maxMatches = Math.max(1, limit);
        boolean truncated = false;
        while (count < maxMatches && sb.length() < DEFAULT_SEARCH_MAX_CHARS) {
            int index = lowerContent.indexOf(lowerQuery, from);
            if (index < 0) {
                break;
            }
            int start = Math.max(0, index - 160);
            int end = Math.min(content.length(), index + query.length() + 240);
            String match = "\nMatch " + (count + 1) + " at " + index + ":\n"
                    + content.substring(start, end) + "\n";
            if (sb.length() + match.length() > DEFAULT_SEARCH_MAX_CHARS) {
                int remaining = Math.max(0, DEFAULT_SEARCH_MAX_CHARS - sb.length());
                if (remaining > 0) {
                    sb.append(match, 0, Math.min(remaining, match.length()));
                }
                truncated = true;
                break;
            }
            sb.append(match);
            count++;
            from = index + Math.max(1, query.length());
        }
        if (count == 0) {
            sb.append("No matches.");
        }
        if (truncated || count >= maxMatches) {
            sb.append("\n[truncated] Search output is limited to ")
                    .append(DEFAULT_SEARCH_MAX_CHARS)
                    .append(" chars and ")
                    .append(maxMatches)
                    .append(" matches. Use a more specific query or context_ref(action='read', start='...', maxChars='...') for a focused range.");
        }
        return sb.toString();
    }

    private String summary(String refId) {
        Optional<StoredResult> result = resultStore.find(refId);
        if (result.isEmpty()) {
            return "Error: context reference not found: " + refId;
        }
        StoredResult stored = result.get();
        return "Context reference: " + stored.getRefId() + "\n"
                + "Source type: " + nullSafe(stored.getSourceType()) + "\n"
                + "Source: " + nullSafe(stored.getSourceName()) + "\n"
                + "Content length: " + stored.getContentLength() + "\n"
                + "Created at: " + stored.getCreatedAt() + "\n\n"
                + "Preview:\n" + nullSafe(stored.getPreview());
    }

    private String list(int limit) {
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        String sessionKey = scope != null ? scope.sessionKey() : null;
        String runId = scope != null ? scope.runId() : null;
        List<ContextRef> refs = resultStore.list(sessionKey, runId, Math.max(1, limit));
        if (refs.isEmpty()) {
            return "No context references found for the current run/session.";
        }
        return refs.stream()
                .map(ref -> "- " + ref.getRefId()
                        + " | source=" + nullSafe(ref.getSourceName())
                        + " | length=" + ref.getContentLength()
                        + " | createdAt=" + ref.getCreatedAt())
                .collect(Collectors.joining("\n"));
    }

    private int readMaxChars() {
        return config != null && config.getAgent() != null && config.getAgent().getContextRefReadMaxChars() > 0
                ? config.getAgent().getContextRefReadMaxChars()
                : DEFAULT_READ_MAX_CHARS;
    }

    private int readTurnBudgetChars() {
        return config != null && config.getAgent() != null && config.getAgent().getContextRefReadTurnBudgetChars() > 0
                ? config.getAgent().getContextRefReadTurnBudgetChars()
                : DEFAULT_READ_TURN_BUDGET_CHARS;
    }

    private ReadBudgetDecision reserveReadBudget(int safeMax, int requestedMax) {
        AgentExecutionContext.ExecutionScope scope = AgentExecutionContext.getCurrentScope();
        if (scope == null || scope.sessionKey() == null || scope.runId() == null) {
            return new ReadBudgetDecision(true, safeMax, 0);
        }
        int budget = readTurnBudgetChars();
        if (budget <= 0) {
            return new ReadBudgetDecision(true, safeMax, 0);
        }
        String key = scope.sessionKey() + ":" + scope.runId();
        AtomicInteger used = readBudgetByRun.computeIfAbsent(key, ignored -> new AtomicInteger());
        int before = used.get();
        if (before + safeMax <= budget) {
            used.addAndGet(safeMax);
            return new ReadBudgetDecision(true, safeMax, before);
        }
        if (requestedMax <= BUDGET_EXCEEDED_SMALL_READ_CHARS) {
            int focusedChars = Math.min(safeMax, BUDGET_EXCEEDED_SMALL_READ_CHARS);
            used.addAndGet(focusedChars);
            return new ReadBudgetDecision(true, focusedChars, before);
        }
        return new ReadBudgetDecision(false, 0, before);
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    private record ReadBudgetDecision(boolean allowed, int allowedChars, int usedBefore) {
    }
}

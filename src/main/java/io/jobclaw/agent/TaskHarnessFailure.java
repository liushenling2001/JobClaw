package io.jobclaw.agent;

public record TaskHarnessFailure(TaskHarnessFailureKind kind, String reason, String evidence) {
}

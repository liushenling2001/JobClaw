package io.jobclaw.agent;

public interface TaskHarnessVerifierRule {

    TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure);
}

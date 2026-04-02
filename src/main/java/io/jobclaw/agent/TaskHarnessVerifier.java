package io.jobclaw.agent;

public interface TaskHarnessVerifier {

    TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure);
}

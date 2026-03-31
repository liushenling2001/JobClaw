package io.jobclaw.agent;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompositeTaskHarnessVerifier implements TaskHarnessVerifier {

    private final List<TaskHarnessVerifierRule> rules;

    public CompositeTaskHarnessVerifier(List<TaskHarnessVerifierRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        for (TaskHarnessVerifierRule rule : rules) {
            TaskHarnessVerificationResult result = rule.verify(run, finalResponse, failure);
            if (!result.success()) {
                return result;
            }
        }
        return TaskHarnessVerificationResult.ok("All verifiers accepted the final response");
    }
}

package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Order(200)
public class TestCommandTaskHarnessVerifierRule implements TaskHarnessVerifierRule {

    private static final List<String> TEST_COMMAND_MARKERS = List.of(
            "mvn test",
            "mvn verify",
            "./gradlew test",
            "gradle test",
            "npm test",
            "pnpm test",
            "yarn test",
            "pytest",
            "go test",
            "cargo test"
    );

    private static final List<String> FAILURE_OUTPUT_MARKERS = List.of(
            "build failed",
            "build failure",
            "tests failed",
            "test failed",
            "failure:",
            "failures:",
            " failed",
            " errors",
            " error",
            "error:",
            "exception:",
            "exit code: 1",
            "exit code: 2"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null) {
            return TaskHarnessVerificationResult.ok("Execution failure is handled by the default verifier");
        }

        List<TaskHarnessStep> testCommandStarts = run.getSteps().stream()
                .filter(step -> "TOOL_START".equals(step.metadata().get("eventType")))
                .filter(this::isTestCommand)
                .toList();

        if (testCommandStarts.isEmpty()) {
            return TaskHarnessVerificationResult.ok("No test command detected");
        }

        for (TaskHarnessStep startStep : testCommandStarts) {
            String toolId = stringValue(startStep.metadata().get("toolId"));
            String request = stringValue(startStep.metadata().get("request"));

            if (hasMatchingToolError(run.getSteps(), toolId)) {
                return TaskHarnessVerificationResult.fail("TEST_COMMAND", "Detected failed test command: " + request);
            }

            TaskHarnessStep toolEnd = findLatestMatchingStep(run.getSteps(), "TOOL_END", toolId);
            if (toolEnd == null) {
                return TaskHarnessVerificationResult.fail("TEST_COMMAND", "Test command finished without success evidence: " + request);
            }

            TaskHarnessStep toolOutput = findLatestMatchingStep(run.getSteps(), "TOOL_OUTPUT", toolId);
            if (toolOutput != null && containsFailureMarker(toolOutput.detail())) {
                return TaskHarnessVerificationResult.fail("TEST_COMMAND", "Test output indicates failure: " + request);
            }
        }

        return TaskHarnessVerificationResult.ok("Test command execution verified");
    }

    private boolean isTestCommand(TaskHarnessStep step) {
        String request = stringValue(step.metadata().get("request")).toLowerCase(Locale.ROOT);
        return TEST_COMMAND_MARKERS.stream().anyMatch(request::contains);
    }

    private boolean hasMatchingToolError(List<TaskHarnessStep> steps, String toolId) {
        return findLatestMatchingStep(steps, "TOOL_ERROR", toolId) != null;
    }

    private TaskHarnessStep findLatestMatchingStep(List<TaskHarnessStep> steps, String eventType, String toolId) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            TaskHarnessStep step = steps.get(i);
            Map<String, Object> metadata = step.metadata();
            if (eventType.equals(metadata.get("eventType")) && toolId.equals(stringValue(metadata.get("toolId")))) {
                return step;
            }
        }
        return null;
    }

    private boolean containsFailureMarker(String detail) {
        String normalized = stringValue(detail).toLowerCase(Locale.ROOT);
        return FAILURE_OUTPUT_MARKERS.stream().anyMatch(normalized::contains);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

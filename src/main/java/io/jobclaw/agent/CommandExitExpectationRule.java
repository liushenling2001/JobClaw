package io.jobclaw.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@Order(250)
public class CommandExitExpectationRule implements TaskHarnessVerifierRule {

    private static final Set<String> COMMAND_TOOLS = Set.of("run_command", "exec");
    private static final List<String> NON_ZERO_EXIT_MARKERS = List.of(
            "exit code: 1",
            "exit code: 2",
            "exit code: 127",
            "error executing command",
            "command timed out"
    );

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null) {
            return TaskHarnessVerificationResult.ok("Execution failure is handled by the default verifier");
        }

        for (TaskHarnessStep step : run.getSteps()) {
            Map<String, Object> metadata = step.metadata();
            if (!"TOOL_OUTPUT".equals(metadata.get("eventType"))) {
                continue;
            }
            String toolName = stringValue(metadata.get("toolName")).toLowerCase(Locale.ROOT);
            if (!COMMAND_TOOLS.contains(toolName)) {
                continue;
            }
            if (containsNonZeroExit(step.detail())) {
                String request = stringValue(metadata.get("request"));
                return TaskHarnessVerificationResult.fail(
                        "COMMAND_EXIT",
                        "Command output indicates non-zero exit: " + request
                );
            }
        }

        return TaskHarnessVerificationResult.ok("No non-zero command exit detected");
    }

    private boolean containsNonZeroExit(String detail) {
        String normalized = stringValue(detail).toLowerCase(Locale.ROOT);
        return NON_ZERO_EXIT_MARKERS.stream().anyMatch(normalized::contains);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

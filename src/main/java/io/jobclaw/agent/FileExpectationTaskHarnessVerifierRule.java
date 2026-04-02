package io.jobclaw.agent;

import io.jobclaw.config.Config;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(300)
public class FileExpectationTaskHarnessVerifierRule implements TaskHarnessVerifierRule {

    private static final Set<String> MUTATING_FILE_TOOLS = Set.of("write_file", "edit_file", "append_file");
    private static final Pattern JSON_PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PARAM_PATH_PATTERN = Pattern.compile("path\\s*[=:]\\s*['\"]?([^,'\"\\n}]+)");

    private final Config config;

    public FileExpectationTaskHarnessVerifierRule(Config config) {
        this.config = config;
    }

    @Override
    public TaskHarnessVerificationResult verify(TaskHarnessRun run, String finalResponse, Throwable failure) {
        if (failure != null) {
            return TaskHarnessVerificationResult.ok("Execution failure is handled by the default verifier");
        }

        Set<String> expectedPaths = new LinkedHashSet<>();
        for (TaskHarnessStep step : run.getSteps()) {
            if (!"TOOL_START".equals(step.metadata().get("eventType"))) {
                continue;
            }
            String toolName = stringValue(step.metadata().get("toolName")).toLowerCase(Locale.ROOT);
            if (!MUTATING_FILE_TOOLS.contains(toolName)) {
                continue;
            }
            expectedPaths.addAll(extractPaths(stringValue(step.metadata().get("request"))));
        }

        if (expectedPaths.isEmpty()) {
            return TaskHarnessVerificationResult.ok("No mutating file operation detected");
        }

        for (String expectedPath : expectedPaths) {
            Path resolvedPath = resolvePath(expectedPath);
            if (!Files.exists(resolvedPath)) {
                return TaskHarnessVerificationResult.fail(
                        "FILE_EXPECTATION",
                        "Expected file missing after write operation: " + expectedPath
                );
            }
        }

        return TaskHarnessVerificationResult.ok("File operation results verified");
    }

    private List<String> extractPaths(String request) {
        if (request == null || request.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collectMatches(paths, JSON_PATH_PATTERN.matcher(request));
        collectMatches(paths, PARAM_PATH_PATTERN.matcher(request));
        return List.copyOf(paths);
    }

    private void collectMatches(Set<String> paths, Matcher matcher) {
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path != null && !path.isBlank()) {
                paths.add(path.trim());
            }
        }
    }

    private Path resolvePath(String path) {
        Path input = Paths.get(path);
        if (input.isAbsolute()) {
            return input.normalize();
        }
        return Paths.get(config.getWorkspacePath()).resolve(input).normalize();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

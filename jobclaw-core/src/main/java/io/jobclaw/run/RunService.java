package io.jobclaw.run;

import io.jobclaw.agent.AgentExecutionOptions;
import io.jobclaw.agent.AgentOrchestrator;
import io.jobclaw.agent.ExecutionEvent;
import io.jobclaw.agent.ExecutionTraceService;
import io.jobclaw.agent.runtime.AgentRunIds;
import io.jobclaw.config.Config;
import io.jobclaw.workspace.WorkspaceContext;
import io.jobclaw.workspace.WorkspaceInspector;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RunService {
    private static final Pattern CONTEXT_REF_PATTERN = Pattern.compile("(?m)^refId:\\s*(\\S+)");
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile(
            "(?:[A-Za-z]:\\\\[^\\s\"'<>|]+|/[^\\s\"'<>|]+)"
    );

    private final Config config;
    private final AgentOrchestrator orchestrator;
    private final ExecutionTraceService executionTraceService;
    private final WorkspaceInspector workspaceInspector;
    private final RunStore runStore;

    public RunService(Config config,
                      AgentOrchestrator orchestrator,
                      ExecutionTraceService executionTraceService,
                      WorkspaceInspector workspaceInspector,
                      RunStore runStore) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.executionTraceService = executionTraceService;
        this.workspaceInspector = workspaceInspector;
        this.runStore = runStore;
    }

    public RunRecord startForeground(RunRequest request, Consumer<ExecutionEvent> eventConsumer) throws Exception {
        WorkspaceContext workspace = workspaceInspector.inspect(request.cwd());
        RunRecord record = createRecord(request, workspace);
        runStore.save(record);

        Set<String> artifacts = new LinkedHashSet<>();
        Set<String> refs = new LinkedHashSet<>();
        Consumer<ExecutionEvent> callback = event -> {
            try {
                collectFromEvent(event, artifacts, refs);
                runStore.appendEvent(record.getRunId(), event);
                executionTraceService.publish(event);
                if (eventConsumer != null) {
                    eventConsumer.accept(event);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        record.setStatus(RunStatus.RUNNING);
        record.setStartedAt(Instant.now());
        record.setUpdatedAt(record.getStartedAt());
        record.setHeartbeatAt(record.getStartedAt());
        runStore.save(record);

        AgentExecutionOptions options = AgentExecutionOptions.builder()
                .runId(record.getRunId())
                .parentRunId(record.getParentRunId())
                .projectRoot(record.getProjectRoot())
                .cwd(record.getCwd())
                .source(record.getSource())
                .approvalMode(record.getApprovalMode())
                .sandboxMode(record.getSandboxMode())
                .agentId(record.getAgentId())
                .build();

        try {
            String response = orchestrator.process(record.getSessionKey(), record.getTask(), options, callback);
            record.setFinalResponse(response);
            collectFromText(response, artifacts, refs);
            record.setStatus(RunStatus.SUCCEEDED);
            record.setExitCode(0);
            record.setCompletedAt(Instant.now());
        } catch (Exception e) {
            record.setStatus(RunStatus.FAILED);
            record.setExitCode(1);
            record.setError(e.getMessage());
            record.setCompletedAt(Instant.now());
            throw e;
        } finally {
            record.setUpdatedAt(Instant.now());
            record.setHeartbeatAt(record.getUpdatedAt());
            record.setArtifactPaths(new ArrayList<>(artifacts));
            record.setContextRefIds(new ArrayList<>(refs));
            runStore.saveArtifacts(record.getRunId(), record.getArtifactPaths());
            runStore.save(record);
        }
        return record;
    }

    public RunRecord resumeForeground(String runId, Consumer<ExecutionEvent> eventConsumer) throws Exception {
        RunRecord previous = getRequired(runId);
        String prompt = """
                Continue the previous JobClaw run.

                Previous run: %s
                Previous status: %s
                Previous task:
                %s

                Known artifacts:
                %s

                Last error:
                %s

                Resume from the last safe point. Verify existing artifacts before rewriting them.
                """.formatted(
                previous.getRunId(),
                previous.getStatus(),
                previous.getTask(),
                String.join("\n", safeList(previous.getArtifactPaths())),
                previous.getError() != null ? previous.getError() : ""
        );
        return startForeground(new RunRequest(
                prompt,
                previous.getSessionKey(),
                previous.getProjectRoot(),
                previous.getCwd(),
                "cli",
                previous.getApprovalMode(),
                previous.getSandboxMode(),
                previous.getRunId()
        ), eventConsumer);
    }

    public RunRecord getRequired(String runId) throws Exception {
        return runStore.get(runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }

    public List<RunRecord> listRuns(int limit) throws Exception {
        return runStore.list(limit);
    }

    public List<ExecutionEvent> readEvents(String runId, int limit) throws Exception {
        return runStore.readEvents(runId, limit);
    }

    public List<String> readArtifacts(String runId) throws Exception {
        RunRecord record = getRequired(runId);
        List<String> persisted = runStore.readArtifacts(runId);
        if (!persisted.isEmpty()) {
            return persisted;
        }
        return record.getArtifactPaths() != null ? record.getArtifactPaths() : List.of();
    }

    private RunRecord createRecord(RunRequest request, WorkspaceContext workspace) {
        Instant now = Instant.now();
        RunRecord record = new RunRecord();
        record.setRunId(AgentRunIds.newTopLevelRunId());
        record.setSessionKey(firstNonBlank(request.sessionKey(), defaultSessionKey(workspace)));
        record.setResumedFromRunId(request.resumedFromRunId());
        record.setStatus(RunStatus.QUEUED);
        record.setTask(request.task());
        record.setSource(firstNonBlank(request.source(), "cli"));
        record.setStateRoot(config.getWorkspacePath());
        record.setProjectRoot(workspace.projectRoot());
        record.setCwd(firstNonBlank(request.cwd(), workspace.cwd(), workspace.projectRoot()));
        record.setGitRoot(workspace.gitRoot());
        record.setGitBranch(workspace.gitBranch());
        record.setDirty(workspace.dirty());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setModel(config.getAgent().getModel());
        record.setAgentId("assistant");
        record.setApprovalMode(firstNonBlank(request.approvalMode(), "ask"));
        record.setSandboxMode(firstNonBlank(request.sandboxMode(), "workspace-write"));
        return record;
    }

    private String defaultSessionKey(WorkspaceContext workspace) {
        String projectName = "project";
        try {
            projectName = Path.of(workspace.projectRoot()).getFileName().toString();
        } catch (Exception ignored) {
            // Keep fallback.
        }
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
        return "cli-" + safeSegment(projectName) + "-" + timestamp;
    }

    private void collectFromEvent(ExecutionEvent event, Set<String> artifacts, Set<String> refs) {
        if (event == null) {
            return;
        }
        collectFromText(event.getContent(), artifacts, refs);
        Object path = event.getMetadata().get("path");
        if (path != null) {
            artifacts.add(String.valueOf(path));
        }
        Object artifactPath = event.getMetadata().get("artifactPath");
        if (artifactPath != null) {
            artifacts.add(String.valueOf(artifactPath));
        }
        Object finalArtifactPath = event.getMetadata().get("finalArtifactPath");
        if (finalArtifactPath != null) {
            artifacts.add(String.valueOf(finalArtifactPath));
        }
    }

    private void collectFromText(String text, Set<String> artifacts, Set<String> refs) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher refMatcher = CONTEXT_REF_PATTERN.matcher(text);
        while (refMatcher.find()) {
            refs.add(refMatcher.group(1));
        }
        Matcher pathMatcher = ABSOLUTE_PATH_PATTERN.matcher(text);
        while (pathMatcher.find()) {
            String path = pathMatcher.group();
            if (path.length() > 3) {
                artifacts.add(path);
            }
        }
    }

    private List<String> safeList(List<String> value) {
        return value != null ? value : List.of();
    }

    private String safeSegment(String value) {
        return value == null || value.isBlank() ? "project" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

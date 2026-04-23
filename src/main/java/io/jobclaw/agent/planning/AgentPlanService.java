package io.jobclaw.agent.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.TaskHarnessPhase;
import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.completion.DoneDefinition;
import io.jobclaw.agent.completion.ContractSource;
import io.jobclaw.agent.completion.WorklistRequirementSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentPlanService {

    private static final Logger logger = LoggerFactory.getLogger(AgentPlanService.class);
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("(?i)([A-Z]:\\\\[^\\r\\n\"'<>|]+)");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskPlanningPolicy fallbackPolicy;

    public AgentPlanService(TaskPlanningPolicy fallbackPolicy) {
        this.fallbackPolicy = fallbackPolicy;
    }

    public TaskPlan plan(AgentLoop plannerAgent, String taskInput) {
        TaskPlan fallback = fallbackPolicy.decide(taskInput);
        if (fallback.planningMode() == TaskPlanningMode.DIRECT) {
            return fallback;
        }
        if (plannerAgent == null) {
            return fallback;
        }
        try {
            String response = plannerAgent.callLLM(buildPrompt(taskInput, fallback), null);
            TaskPlan agentPlan = parse(response, fallback, taskInput);
            if (agentPlan != null) {
                return agentPlan;
            }
        } catch (Exception e) {
            logger.warn("Agent plan generation failed, falling back to policy plan: {}", e.getMessage());
        }
        return fallback;
    }

    TaskPlan parse(String response, TaskPlan fallback, String taskInput) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(response));
            TaskPlanningMode mode = enumValue(TaskPlanningMode.class, text(root, "planningMode"), fallback.planningMode());
            DeliveryType deliveryType = enumValue(DeliveryType.class, text(root, "deliveryType"), fallback.doneDefinition().deliveryType());
            boolean requiresWorklist = bool(root, "requiresWorklist", mode == TaskPlanningMode.WORKLIST);
            if (mode == TaskPlanningMode.WORKLIST) {
                requiresWorklist = true;
            }
            if (requiresWorklist) {
                mode = TaskPlanningMode.WORKLIST;
                deliveryType = deliveryType == DeliveryType.ANSWER ? DeliveryType.BATCH_RESULTS : deliveryType;
            }

            boolean requiresFinalSummary = bool(root, "requiresFinalSummary",
                    deliveryType == DeliveryType.ANSWER
                            || deliveryType == DeliveryType.DOCUMENT_SUMMARY
                            || deliveryType == DeliveryType.BATCH_RESULTS);
            List<PlanStep> steps = parseSteps(root.get("steps"));
            if (mode != TaskPlanningMode.DIRECT && steps.isEmpty()) {
                steps = fallback.steps();
            }
            List<String> outputDirectories = outputDirectories(root.get("outputDirectories"), taskInput);
            DoneDefinition doneDefinition = doneDefinition(mode, deliveryType, requiresWorklist, requiresFinalSummary, outputDirectories);
            String reason = text(root, "reason");
            if (reason.isBlank()) {
                reason = "agent_generated_plan";
            }
            return new TaskPlan(mode, doneDefinition, reason, steps);
        } catch (Exception e) {
            logger.warn("Could not parse agent plan response, falling back: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String taskInput, TaskPlan fallback) {
        return """
                You are JobClaw's planning agent. Decide the execution contract for the task.
                Return JSON only. Do not include markdown.

                Rules:
                - DIRECT is only for ordinary Q&A that can be answered without tools or multi-step execution.
                - PHASED is for most multi-step tasks, including reading multiple files then generating one report/document.
                - WORKLIST is only when the user explicitly asks for subtasks/worklist/spawn/子任务, or the task truly requires tracked independent items.
                - Do not choose WORKLIST only because the task mentions a folder, batch, multiple files, or all PDFs.
                - deliveryType must be one of ANSWER, DOCUMENT_SUMMARY, FILE_ARTIFACT, PATCH, BATCH_RESULTS.
                - If the task asks to create/save/write a file, use FILE_ARTIFACT.
                - If the task asks to modify an existing file/code, use PATCH.
                - If the task asks to summarize/read documents without creating a file, use DOCUMENT_SUMMARY.
                - Steps must be concise execution steps. Harness will enforce this plan; do not include speculative steps.
                - outputDirectories should contain exact directories where final files must be saved. If the user says "current directory" after naming an input folder, use that named input folder, not the agent workspace.

                JSON schema:
                {
                  "planningMode": "DIRECT|PHASED|WORKLIST",
                  "deliveryType": "ANSWER|DOCUMENT_SUMMARY|FILE_ARTIFACT|PATCH|BATCH_RESULTS",
                  "requiresWorklist": false,
                  "requiresFinalSummary": true,
                  "outputDirectories": ["D:\\\\target-dir"],
                  "reason": "short reason",
                  "steps": [
                    {"id": "inspect-inputs", "goal": "...", "completion": "..."}
                  ]
                }

                Fallback policy suggestion:
                planningMode=%s
                deliveryType=%s
                requiresWorklist=%s

                Task:
                %s
                """.formatted(
                fallback.planningMode(),
                fallback.doneDefinition().deliveryType(),
                fallback.doneDefinition().requiresWorklist(),
                taskInput
        );
    }

    private List<PlanStep> parseSteps(JsonNode stepsNode) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return List.of();
        }
        List<PlanStep> steps = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            String id = text(node, "id");
            String goal = text(node, "goal");
            String completion = text(node, "completion");
            if (!id.isBlank() && !goal.isBlank()) {
                steps.add(new PlanStep(id, goal, completion));
            }
        }
        return steps;
    }

    private DoneDefinition doneDefinition(TaskPlanningMode planningMode,
                                           DeliveryType deliveryType,
                                           boolean requiresWorklist,
                                           boolean requiresFinalSummary,
                                           List<String> outputDirectories) {
        List<String> rules = switch (deliveryType) {
            case BATCH_RESULTS -> List.of("worklist_planned", "pending_subtasks_zero", "final_summary");
            case FILE_ARTIFACT -> List.of("artifact_exists");
            case PATCH -> List.of("file_change");
            case DOCUMENT_SUMMARY -> List.of("document_summary");
            case ANSWER -> List.of("usable_answer");
        };
        List<TaskHarnessPhase> phases = switch (deliveryType) {
            case ANSWER -> List.of();
            case BATCH_RESULTS -> List.of(TaskHarnessPhase.PLAN, TaskHarnessPhase.ACT);
            default -> List.of(TaskHarnessPhase.ACT);
        };
        return new DoneDefinition(
                planningMode,
                deliveryType,
                List.of(),
                outputDirectories,
                phases,
                requiresWorklist,
                requiresWorklist ? WorklistRequirementSource.AGENT_PLAN : WorklistRequirementSource.NONE,
                requiresFinalSummary,
                true,
                ContractSource.AGENT_PLAN,
                rules
        );
    }

    private List<String> outputDirectories(JsonNode node, String taskInput) {
        LinkedHashSet<String> directories = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    directories.add(value);
                }
            }
        }
        if (directories.isEmpty()) {
            directories.addAll(extractLikelyDirectories(taskInput));
        }
        return List.copyOf(directories);
    }

    private List<String> extractLikelyDirectories(String taskInput) {
        if (taskInput == null || taskInput.isBlank()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = WINDOWS_PATH_PATTERN.matcher(taskInput);
        while (matcher.find()) {
            String candidate = cleanDirectoryCandidate(matcher.group(1));
            if (!candidate.matches("(?i).+\\.(pdf|docx?|xlsx?|txt|md|pptx?)$")) {
                paths.add(candidate);
            }
        }
        return List.copyOf(paths);
    }

    private String cleanDirectoryCandidate(String value) {
        String cleaned = value == null ? "" : value.trim();
        String[] stops = {"”", "“", "\"", "'", "`", "\r", "\n", " 目录", "目录", " 文件夹", "文件夹",
                " 下", "下的", "下面", " 中", "里", "，", ",", "。", "；", ";"};
        for (String stop : stops) {
            int index = cleaned.indexOf(stop);
            if (index >= 0) {
                cleaned = cleaned.substring(0, index).trim();
            }
        }
        while (cleaned.endsWith("\\") || cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int jsonStart = trimmed.indexOf('{', fenceStart);
            int fenceEnd = trimmed.indexOf("```", fenceStart + 3);
            if (jsonStart >= 0 && fenceEnd > jsonStart) {
                return trimmed.substring(jsonStart, fenceEnd).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("").trim();
    }

    private boolean bool(JsonNode node, String field, boolean fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        return node.get(field).asBoolean(fallback);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}

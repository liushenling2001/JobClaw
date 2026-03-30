package io.jobclaw.agent.catalog;

import io.jobclaw.agent.AgentDefinition;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.agent.ExecutionEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentCommandService {

    private final AgentCatalogService agentCatalogService;
    private final AgentIntentRouter intentRouter;
    private final AgentLoop agentLoop;

    public AgentCommandService(AgentCatalogService agentCatalogService,
                               AgentIntentRouter intentRouter,
                               AgentLoop agentLoop) {
        this.agentCatalogService = agentCatalogService;
        this.intentRouter = intentRouter;
        this.agentLoop = agentLoop;
    }

    public Optional<String> tryHandle(String sessionKey,
                                      String userContent,
                                      Consumer<ExecutionEvent> eventCallback) {
        AgentIntentRouter.AgentIntent intent = intentRouter.route(userContent);
        return switch (intent.type()) {
            case CREATE_AGENT -> Optional.of(handleCreate(intent));
            case INVOKE_AGENT -> handleInvoke(sessionKey, intent, eventCallback);
            case DEFAULT_CHAT -> Optional.empty();
        };
    }

    private String handleCreate(AgentIntentRouter.AgentIntent intent) {
        String displayName = intent.agentName() != null && !intent.agentName().isBlank()
                ? intent.agentName()
                : "专用助手";
        String description = deriveDescription(intent.task());
        String systemPrompt = buildSystemPrompt(displayName, description);
        List<String> aliases = new ArrayList<>();
        aliases.add(displayName);
        aliases.add(displayName + "智能体");

        AgentCatalogEntry entry = agentCatalogService.createAgent(
                toCode(displayName),
                displayName,
                description,
                systemPrompt,
                aliases,
                List.of("read_file", "write_file", "list_dir", "spawn", "collaborate"),
                List.of(),
                Map.of(),
                "agent:" + toCode(displayName)
        );

        return "已创建智能体 `" + entry.displayName() + "`。\n\n" +
                "调用方式示例：`使用" + entry.displayName() + "，帮我开展具体工作`";
    }

    private Optional<String> handleInvoke(String sessionKey,
                                          AgentIntentRouter.AgentIntent intent,
                                          Consumer<ExecutionEvent> eventCallback) {
        if (intent.agentName() == null || intent.agentName().isBlank()) {
            return Optional.empty();
        }
        Optional<AgentDefinition> definition = agentCatalogService.resolveDefinition(intent.agentName());
        if (definition.isEmpty()) {
            return Optional.empty();
        }

        String task = intent.task() != null && !intent.task().isBlank()
                ? intent.task()
                : "请开始工作。";
        String response = agentLoop.processWithDefinition(sessionKey, task, definition.get(), eventCallback);
        return Optional.of(response);
    }

    private String buildSystemPrompt(String displayName, String description) {
        return "你是 `" + displayName + "`。\n" +
                "你的专属职责是：" + description + "。\n" +
                "优先围绕该职责完成任务，必要时使用工具，但不要偏离角色边界。";
    }

    private String deriveDescription(String taskTail) {
        if (taskTail == null || taskTail.isBlank()) {
            return "处理用户指定的专项工作";
        }
        String normalized = taskTail
                .replaceFirst("^(，|,|：|:)", "")
                .replace("以后直接启用", "")
                .replace("以后直接调用", "")
                .trim();
        if (normalized.isBlank()) {
            return "处理用户指定的专项工作";
        }
        return normalized;
    }

    private String toCode(String displayName) {
        String ascii = displayName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "_")
                .replaceAll("^_+|_+$", "");
        if (ascii.isBlank()) {
            return "custom_agent";
        }
        return ascii.length() > 48 ? ascii.substring(0, 48) : ascii;
    }
}

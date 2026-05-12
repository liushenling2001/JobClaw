package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentLoopToolSelectionTest {

    @Test
    void shouldUseFullToolsetWhenNoExplicitAllowlist() throws Exception {
        AgentLoop loop = loopWithTools("memory", "skills", "read_pdf", "spawn", "run_command");

        ToolCallback[] selected = invokeFilter(loop, null, "解释一下上下文压缩");
        List<String> names = names(selected);

        assertTrue(names.contains("memory"));
        assertTrue(names.contains("skills"));
        assertTrue(names.contains("read_pdf"));
        assertTrue(names.contains("spawn"));
        assertTrue(names.contains("run_command"));
    }

    @Test
    void shouldRespectExplicitAgentAllowlistWithoutDynamicNarrowing() throws Exception {
        AgentLoop loop = loopWithTools("memory", "skills", "read_pdf", "spawn", "run_command");
        AgentDefinition definition = AgentDefinition.builder()
                .code("pdf-agent")
                .displayName("PDF Agent")
                .systemPrompt("Read PDFs")
                .allowedTools(List.of("read_pdf"))
                .build();

        ToolCallback[] selected = invokeFilter(loop, definition, "解释一下上下文压缩");
        List<String> names = names(selected);

        assertTrue(names.contains("read_pdf"));
        assertFalse(names.contains("memory"));
    }

    @Test
    void shouldUseFullToolsetForSkillInvocation() throws Exception {
        AgentLoop loop = loopWithTools(
                "memory", "skills", "read_file", "read_word", "read_pdf", "read_excel",
                "list_dir", "append_file", "manifest", "context_ref", "run_command"
        );

        ToolCallback[] selected = invokeFilter(loop, null, "使用 batch-document-extract-excel 技能处理文档");
        List<String> names = names(selected);

        assertTrue(names.contains("skills"));
        assertTrue(names.contains("read_word"));
        assertTrue(names.contains("list_dir"));
        assertTrue(names.contains("append_file"));
        assertTrue(names.contains("manifest"));
        assertTrue(names.contains("context_ref"));
    }

    private AgentLoop loopWithTools(String... toolNames) {
        ToolCallback[] callbacks = Arrays.stream(toolNames)
                .map(this::tool)
                .toArray(ToolCallback[]::new);
        Config config = Config.defaultConfig();
        config.getAgent().setProvider("ollama");
        config.getAgent().setModel("llama3.1");
        return new AgentLoop(
                config,
                new SessionManager(),
                callbacks,
                mock(ContextBuilder.class),
                mock(ContextAssembler.class),
                mock(ContextAssemblyPolicy.class),
                mock(SummaryService.class)
        );
    }

    private ToolCallback[] invokeFilter(AgentLoop loop, AgentDefinition definition, String userContent) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod(
                "filterToolsByDefinition",
                AgentDefinition.class,
                String.class
        );
        method.setAccessible(true);
        return (ToolCallback[]) method.invoke(loop, definition, userContent);
    }

    private List<String> names(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }
}

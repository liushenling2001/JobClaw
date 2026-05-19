package io.jobclaw.agent;

import io.jobclaw.config.Config;
import io.jobclaw.context.ContextAssembler;
import io.jobclaw.context.ContextAssemblyPolicy;
import io.jobclaw.agent.completion.ActiveExecutionRegistry;
import io.jobclaw.agent.completion.CompletionRegistry;
import io.jobclaw.agent.manifest.ActiveManifestRegistry;
import io.jobclaw.agent.skill.ActiveSkillRegistry;
import io.jobclaw.context.result.NoopResultStore;
import io.jobclaw.runtime.provider.ProviderRuntime;
import io.jobclaw.session.SessionManager;
import io.jobclaw.summary.SummaryService;
import io.jobclaw.tools.ManifestTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void managedRunnerShouldUseDefaultReadOnlyToolsWhenSkillDoesNotDeclareAllowlist() throws Exception {
        AgentLoop loop = loopWithTools(
                "skills", "manifest", "write_file", "append_file", "read_file",
                "read_word", "read_pdf", "context_ref", "run_command", "spawn"
        );

        ToolCallback[] selected = invokeManagedRunnerFilter(loop,
                Arrays.stream(new String[]{
                "skills", "manifest", "write_file", "append_file", "read_file",
                "read_word", "read_pdf", "context_ref", "run_command", "spawn"
                }).map(this::tool).toArray(ToolCallback[]::new),
                "session-a",
                "run-1");
        List<String> names = names(selected);

        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("read_word"));
        assertTrue(names.contains("read_pdf"));
        assertTrue(names.contains("context_ref"));
        assertFalse(names.contains("skills"));
        assertFalse(names.contains("manifest"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("append_file"));
        assertFalse(names.contains("run_command"));
        assertFalse(names.contains("spawn"));
    }

    @Test
    void managedRunnerShouldUseSkillDeclaredAllowlist() throws Exception {
        ActiveSkillRegistry skillRegistry = new ActiveSkillRegistry();
        AgentLoop loop = loopWithRegistries(skillRegistry, new ActiveManifestRegistry(),
                "skills", "manifest", "read_file", "mcp", "run_command", "context_ref");
        skillRegistry.activate("session-a", "run-1", "custom", """
                # Skill
                ## Managed Runtime
                mode: runner
                frameworkWrites: item-json,manifest
                itemResultPathTemplate: D:\\tmp\\{{item.safeId}}.txt
                aggregatePathTemplate: D:\\tmp\\index.jsonl
                itemOutput: text
                allowedTools: mcp, context_ref
                ### Item Loop
                Call the declared tool for {{item.id}}.
                """, "E:\\skills\\custom");

        ToolCallback[] selected = invokeManagedRunnerFilter(loop,
                Arrays.stream(new String[]{
                        "skills", "manifest", "read_file", "mcp", "run_command", "context_ref"
                }).map(this::tool).toArray(ToolCallback[]::new),
                "session-a",
                "run-1");
        List<String> names = names(selected);

        assertTrue(names.contains("mcp"));
        assertTrue(names.contains("context_ref"));
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("skills"));
        assertFalse(names.contains("manifest"));
        assertFalse(names.contains("run_command"));
    }

    @Test
    void managedRunnerShouldAskForCreateRepairBeforeFinalAnswer() throws Exception {
        ActiveSkillRegistry skillRegistry = new ActiveSkillRegistry();
        ActiveManifestRegistry manifestRegistry = new ActiveManifestRegistry();
        AgentLoop loop = loopWithRegistries(skillRegistry, manifestRegistry, "manifest", "skills", "list_dir");
        skillRegistry.activate("session-a", "run-1", "batch", """
                # Skill
                ## Managed Runtime
                mode: runner
                frameworkWrites: item-json,jsonl,manifest
                itemResultPathTemplate: {{task.inputDir}}\\items\\{{item.safeId}}.json
                aggregatePathTemplate: {{artifactPath}}
                itemOutput: json_object
                ### Item Loop
                Return JSON for {{item.id}}.
                """, "E:\\skills\\batch");

        String repair = invokeManagedCreateRepair(loop, "session-a", "run-1",
                "Error: managed manifest contract is incomplete. schema is required;", 0);

        assertTrue(repair.contains("JOBCLAW_MANAGED_MANIFEST_CREATE_REPAIR"));
        assertTrue(repair.contains("Do not answer the user yet"));
        assertTrue(repair.contains("manifest.create must include taskKey, items, schema, artifactPath"));
    }

    @Test
    void managedRunnerShouldSelectMultipleItemsWhenSkillDeclaresParallelism() throws Exception {
        AgentLoop loop = loopWithTools("read_file", "context_ref");
        ActiveManifestRegistry registry = new ActiveManifestRegistry();
        LinkedHashMap<String, ManifestTool.ManifestItem> items = new LinkedHashMap<>();
        items.put("a", new ManifestTool.ManifestItem(
                "a", "A", "running", null, null, null, null, Instant.now(), Instant.now()));
        items.put("b", new ManifestTool.ManifestItem(
                "b", "B", "pending", null, null, null, null, Instant.now(), Instant.now()));
        items.put("c", new ManifestTool.ManifestItem(
                "c", "C", "pending", null, null, null, null, Instant.now(), Instant.now()));
        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "session-a", event -> {}, "run-1", null, null, null, null));
        registry.update(new ManifestTool.ManifestRecord(
                "mf-a", "session-a", "run-1", "task", "fingerprint", "{}", "",
                "managed", "", "", items, Instant.now(), Instant.now()));
        ActiveManifestRegistry.ActiveManifestState state =
                registry.findManagedBlockingState("session-a", "run-1").orElseThrow();

        List<?> selected = invokeManagedRunnerSelection(loop, state, 2);

        assertEquals(2, selected.size());
        assertEquals("a", managedRunnerItemId(loop, selected.get(0)));
        assertEquals("b", managedRunnerItemId(loop, selected.get(1)));
        AgentExecutionContext.clear();
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

    private AgentLoop loopWithRegistries(ActiveSkillRegistry skillRegistry,
                                         ActiveManifestRegistry manifestRegistry,
                                         String... toolNames) {
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
                mock(SummaryService.class),
                null,
                null,
                new ProviderRuntime(),
                new ActiveExecutionRegistry(),
                new NoopResultStore(),
                new CompletionRegistry(config),
                skillRegistry,
                manifestRegistry
        );
    }

    private ToolCallback[] invokeManagedRunnerFilter(AgentLoop loop,
                                                     ToolCallback[] tools,
                                                     String sessionKey,
                                                     String runId) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod("filterManagedRunnerTools",
                ToolCallback[].class, String.class, String.class);
        method.setAccessible(true);
        return (ToolCallback[]) method.invoke(loop, tools, sessionKey, runId);
    }

    private String invokeManagedCreateRepair(AgentLoop loop,
                                             String sessionKey,
                                             String runId,
                                             String attemptResponse,
                                             int attempts) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod(
                "buildManagedCreateRepairPrompt",
                String.class,
                String.class,
                String.class,
                int.class
        );
        method.setAccessible(true);
        return (String) method.invoke(loop, sessionKey, runId, attemptResponse, attempts);
    }

    @SuppressWarnings("unchecked")
    private List<?> invokeManagedRunnerSelection(AgentLoop loop,
                                                 ActiveManifestRegistry.ActiveManifestState state,
                                                 int parallelism) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod(
                "selectManagedRunnerStates",
                ActiveManifestRegistry.ActiveManifestState.class,
                int.class
        );
        method.setAccessible(true);
        return (List<?>) method.invoke(loop, state, parallelism);
    }

    private String managedRunnerItemId(AgentLoop loop, Object selectedState) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod(
                "managedRunnerItem",
                ActiveManifestRegistry.ActiveManifestState.class
        );
        method.setAccessible(true);
        ActiveManifestRegistry.ActiveManifestItem item =
                (ActiveManifestRegistry.ActiveManifestItem) method.invoke(loop, selectedState);
        return item.id();
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

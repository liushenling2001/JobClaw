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
import io.jobclaw.skills.SkillInfo;
import io.jobclaw.skills.SkillsService;
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
import static org.mockito.Mockito.when;

class AgentLoopToolSelectionTest {

    @Test
    void shouldUseBaseToolsetWhenNoExplicitAllowlist() throws Exception {
        AgentLoop loop = loopWithTools(
                "memory", "skills", "read_pdf", "spawn", "run_command",
                "context_ref", "manifest", "completion", "user_input", "list_dir", "read_file",
                "write_file", "edit_file", "append_file", "exec"
        );

        ToolCallback[] selected = invokeFilter(loop, null, "解释一下上下文压缩");
        List<String> names = names(selected);

        assertTrue(names.contains("skills"));
        assertTrue(names.contains("context_ref"));
        assertTrue(names.contains("manifest"));
        assertTrue(names.contains("completion"));
        assertTrue(names.contains("user_input"));
        assertTrue(names.contains("list_dir"));
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("write_file"));
        assertTrue(names.contains("edit_file"));
        assertTrue(names.contains("append_file"));
        assertTrue(names.contains("run_command"));
        assertFalse(names.contains("memory"));
        assertFalse(names.contains("read_pdf"));
        assertFalse(names.contains("spawn"));
        assertFalse(names.contains("exec"));
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
    void shouldAddDocumentAndSpreadsheetProfilesForExplicitSkillLikeTask() throws Exception {
        AgentLoop loop = loopWithTools(
                "memory", "skills", "read_file", "read_word", "read_pdf", "read_excel",
                "list_dir", "append_file", "manifest", "context_ref", "run_command", "write_file", "completion"
        );

        ToolCallback[] selected = invokeFilter(loop, null, "使用 batch-document-extract-excel 技能处理文档");
        List<String> names = names(selected);

        assertTrue(names.contains("skills"));
        assertTrue(names.contains("read_word"));
        assertTrue(names.contains("list_dir"));
        assertTrue(names.contains("append_file"));
        assertTrue(names.contains("manifest"));
        assertTrue(names.contains("context_ref"));
        assertTrue(names.contains("run_command"));
        assertTrue(names.contains("read_pdf"));
        assertTrue(names.contains("read_excel"));
        assertFalse(names.contains("memory"));
    }

    @Test
    void shouldAddAgentDeclaredProfilesWithoutModelRouter() throws Exception {
        AgentLoop loop = loopWithTools(
                "skills", "context_ref", "manifest", "completion", "list_dir", "read_file", "run_command",
                "web_search", "web_fetch", "spawn"
        );
        AgentDefinition definition = AgentDefinition.builder()
                .code("research-agent")
                .displayName("Research Agent")
                .systemPrompt("Research")
                .build();
        definition.putMetadata("toolProfiles", List.of("web"));

        ToolCallback[] selected = invokeFilter(loop, definition, "查一下这个页面");
        List<String> names = names(selected);

        assertTrue(names.contains("web_search"));
        assertTrue(names.contains("web_fetch"));
        assertTrue(names.contains("skills"));
        assertFalse(names.contains("spawn"));
    }

    @Test
    void shouldSupplementBuiltInRoleAgentsWithDefaultProfiles() throws Exception {
        AgentLoop loop = loopWithTools(
                "skills", "context_ref", "manifest", "completion", "list_dir", "read_file", "run_command",
                "read_pdf", "read_word", "write_file", "append_file", "web_search", "web_fetch"
        );

        ToolCallback[] writerTools = invokeFilter(loop, AgentDefinition.fromRole(AgentRole.WRITER), "总结这个文件夹");
        List<String> writerNames = names(writerTools);
        assertTrue(writerNames.contains("read_pdf"));
        assertTrue(writerNames.contains("read_word"));
        assertTrue(writerNames.contains("write_file"));
        assertTrue(writerNames.contains("context_ref"));

        ToolCallback[] researcherTools = invokeFilter(loop, AgentDefinition.fromRole(AgentRole.RESEARCHER), "调查这个主题");
        List<String> researcherNames = names(researcherTools);
        assertTrue(researcherNames.contains("read_pdf"));
        assertTrue(researcherNames.contains("web_search"));
        assertTrue(researcherNames.contains("web_fetch"));
    }

    @Test
    void shouldRouteNaturalLanguageRequestsToExpectedProfiles() throws Exception {
        AgentLoop loop = loopWithTools(
                "skills", "context_ref", "manifest", "completion", "list_dir", "read_file", "run_command",
                "write_file", "edit_file", "append_file", "read_pdf", "read_word", "read_excel",
                "web_search", "web_fetch", "message", "memory", "spawn", "collaborate",
                "agent_catalog", "board_write", "board_read", "cron", "mcp", "query_token_usage", "user_input"
        );

        assertSelectedTools(loop, "你看下这个项目能不能编译通过，顺手跑一下测试",
                "edit_file", "write_file");
        assertSelectedTools(loop, "把这个文件夹里的论文整理成一篇综述，最后生成 word",
                "read_pdf", "read_word", "write_file");
        assertSelectedTools(loop, "处理这个 Excel，提取每个 sheet 的统计信息",
                "read_excel", "write_file");
        assertSelectedTools(loop, "帮我查一下 Spring AI 最新文档里 tool calling 的变化",
                "web_search", "web_fetch");
        assertSelectedTools(loop, "明天早上提醒我继续检查这个任务",
                "cron");
        assertSelectedTools(loop, "这个任务让两个子 agent 协作，一个写代码一个审查",
                "spawn", "collaborate", "agent_catalog", "board_write", "board_read");
        assertSelectedTools(loop, "连接 MCP 服务看一下里面有什么资源",
                "mcp");
        assertSelectedTools(loop, "记住这次处理 Excel 的经验，下次遇到类似任务提醒我",
                "memory", "read_excel");
        assertSelectedTools(loop, "查一下今天 token 用量和 API 费用",
                "query_token_usage");
    }

    @Test
    void shouldCarrySelectedToolsForwardWithinSameSession() throws Exception {
        AgentLoop loop = loopWithTools(
                "skills", "context_ref", "manifest", "completion", "list_dir", "read_file", "run_command",
                "write_file", "edit_file", "append_file", "read_pdf", "read_word", "read_excel", "exec"
        );

        ToolCallback[] firstTurn = invokeFilterForSession(loop, null, "读取这个 PDF 并总结", "session-pdf");
        List<String> firstNames = names(firstTurn);
        assertTrue(firstNames.contains("read_pdf"));
        assertTrue(firstNames.contains("read_word"));

        ToolCallback[] secondTurn = invokeFilterForSession(loop, null, "继续处理上面的内容", "session-pdf");
        List<String> secondNames = names(secondTurn);
        assertTrue(secondNames.contains("read_pdf"));
        assertTrue(secondNames.contains("read_word"));
        assertFalse(secondNames.contains("exec"));

        ToolCallback[] otherSession = invokeFilterForSession(loop, null, "继续处理上面的内容", "session-other");
        List<String> otherNames = names(otherSession);
        assertFalse(otherNames.contains("read_pdf"));
        assertFalse(otherNames.contains("read_word"));
    }

    @Test
    void artifactIntentShouldRequireActionAndArtifactObject() {
        assertTrue(AgentLoop.hasArtifactIntent("把这个报告修改后生成一个新docx文档"));
        assertTrue(AgentLoop.hasArtifactIntent("帮我修改这个word文档并保存"));
        assertTrue(AgentLoop.hasArtifactIntent("形成excel，放在当前文件夹"));
        assertFalse(AgentLoop.hasArtifactIntent("解释一下这个文档的主要观点"));
        assertFalse(AgentLoop.hasArtifactIntent("帮我分析一下上下文压缩问题"));
    }

    @Test
    void shouldDetectAbsoluteArtifactPathInFinalCandidate() {
        assertTrue(AgentLoop.containsArtifactPath("结果已保存到 D:\\work\\out\\result.docx"));
        assertFalse(AgentLoop.containsArtifactPath("结果已经保存。"));
    }

    @Test
    void artifactGuardShouldRespectSkillArtifactOptOut() throws Exception {
        ActiveSkillRegistry skillRegistry = new ActiveSkillRegistry();
        AgentLoop loop = loopWithRegistries(skillRegistry, new ActiveManifestRegistry(), "skills", "completion");
        skillRegistry.activate("session-a", "run-1", "analysis", """
                # Skill
                ## Artifact Completion
                requiresArtifact: false
                """, "E:\\skills\\analysis");

        String prompt = invokeArtifactCompletionGuard(loop,
                "session-a",
                "run-1",
                "整理这些材料并生成报告文件",
                "已经完成分析。",
                false);

        assertTrue(prompt.isBlank());
    }

    @Test
    void artifactGuardShouldUseSkillDeclaredArtifactRequirement() throws Exception {
        ActiveSkillRegistry skillRegistry = new ActiveSkillRegistry();
        AgentLoop loop = loopWithRegistries(skillRegistry, new ActiveManifestRegistry(), "skills", "completion");
        skillRegistry.activate("session-a", "run-1", "report", """
                # Skill
                ## Artifact Completion
                requiresArtifact: true
                artifactType: docx
                """, "E:\\skills\\report");

        String prompt = invokeArtifactCompletionGuard(loop,
                "session-a",
                "run-1",
                "总结这些材料",
                "总结完成。",
                false);

        assertTrue(prompt.contains("JOBCLAW_ARTIFACT_COMPLETION_GUARD"));
        assertTrue(prompt.contains("activeSkillRequiresArtifact: true"));
        assertTrue(prompt.contains("artifactType\":\"docx"));
    }

    @Test
    void shouldAddToolsDeclaredByExplicitlyNamedSkill() throws Exception {
        SkillsService skillsService = mock(SkillsService.class);
        SkillInfo skill = new SkillInfo();
        skill.setName("neutral-skill");
        skill.setDescription("Neutral reusable workflow");
        when(skillsService.listSkills()).thenReturn(List.of(skill));
        when(skillsService.loadSkill("neutral-skill")).thenReturn("""
                ---
                name: neutral-skill
                metadata: {"jobclaw":{"toolProfiles":["spreadsheet"],"requiredTools":["read_excel","write_file","exec"]}}
                ---
                # Neutral Skill
                """);
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.getSkillsService()).thenReturn(skillsService);
        AgentLoop loop = loopWithContextBuilder(contextBuilder,
                "skills", "context_ref", "manifest", "completion", "list_dir", "read_file", "run_command",
                "read_excel", "write_file", "spawn", "exec"
        );

        ToolCallback[] selected = invokeFilter(loop, null, "使用 neutral-skill 完成任务");
        List<String> names = names(selected);

        assertTrue(names.contains("read_excel"));
        assertTrue(names.contains("write_file"));
        assertTrue(names.contains("run_command"));
        assertFalse(names.contains("exec"));
        assertFalse(names.contains("spawn"));
    }

    @Test
    void managedRunnerShouldUseDefaultReadOnlyToolsWhenSkillDoesNotDeclareAllowlist() throws Exception {
        AgentLoop loop = loopWithTools(
                "skills", "manifest", "write_file", "append_file", "read_file",
                "read_word", "read_pdf", "context_ref", "user_input", "run_command", "spawn"
        );

        ToolCallback[] selected = invokeManagedRunnerFilter(loop,
                Arrays.stream(new String[]{
                "skills", "manifest", "write_file", "append_file", "read_file",
                "read_word", "read_pdf", "context_ref", "user_input", "run_command", "spawn"
                }).map(this::tool).toArray(ToolCallback[]::new),
                "session-a",
                "run-1");
        List<String> names = names(selected);

        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("read_word"));
        assertTrue(names.contains("read_pdf"));
        assertTrue(names.contains("context_ref"));
        assertTrue(names.contains("user_input"));
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
        return loopWithContextBuilder(mock(ContextBuilder.class), toolNames);
    }

    private AgentLoop loopWithContextBuilder(ContextBuilder contextBuilder, String... toolNames) {
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
                contextBuilder,
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

    private ToolCallback[] invokeFilterForSession(AgentLoop loop,
                                                  AgentDefinition definition,
                                                  String userContent,
                                                  String sessionKey) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod(
                "filterToolsByDefinition",
                AgentDefinition.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        return (ToolCallback[]) method.invoke(loop, definition, userContent, sessionKey);
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

    private String invokeArtifactCompletionGuard(AgentLoop loop,
                                                 String sessionKey,
                                                 String runId,
                                                 String userContent,
                                                 String attemptResponse,
                                                 boolean alreadyIssued) throws Exception {
        Class<?> trackerClass = Arrays.stream(AgentLoop.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ArtifactCompletionTracker"))
                .findFirst()
                .orElseThrow();
        Method method = AgentLoop.class.getDeclaredMethod(
                "buildArtifactCompletionGuardPrompt",
                String.class,
                String.class,
                String.class,
                String.class,
                trackerClass,
                boolean.class
        );
        method.setAccessible(true);
        return (String) method.invoke(loop, sessionKey, runId, userContent, attemptResponse, null, alreadyIssued);
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

    private void assertSelectedTools(AgentLoop loop, String userContent, String... expectedTools) throws Exception {
        List<String> selected = names(invokeFilter(loop, null, userContent));
        for (String expectedTool : expectedTools) {
            assertTrue(selected.contains(expectedTool),
                    () -> "Expected tool " + expectedTool + " for request: " + userContent + ", selected=" + selected);
        }
        assertFalse(selected.contains("exec"), () -> "exec must not be injected for request: " + userContent);
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

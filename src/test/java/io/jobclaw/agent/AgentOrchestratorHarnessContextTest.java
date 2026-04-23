package io.jobclaw.agent;

import io.jobclaw.agent.planning.TaskPlanningPolicy;
import io.jobclaw.agent.planning.TaskPlanningMode;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOrchestratorHarnessContextTest {

    @AfterEach
    void tearDown() {
        AgentExecutionContext.clear();
    }

    @Test
    void shouldExposeHarnessRunIdInsideAgentExecutionContext() {
        AgentRegistry registry = mock(AgentRegistry.class);
        TaskHarnessService harnessService = new TaskHarnessService();

        TaskHarnessRepairPromptBuilder repairPromptBuilder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessRepairStrategy repairStrategy = new TaskHarnessRepairStrategy(Config.defaultConfig());
        AgentLoop loop = mock(AgentLoop.class);
        AtomicReference<String> observedRunId = new AtomicReference<>();

        when(registry.getOrCreateAgent(any(AgentRole.class), anyString())).thenReturn(loop);
        when(loop.process(anyString(), anyString(), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    observedRunId.set(AgentExecutionContext.getCurrentRunId());
                    return "done";
                });

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                Config.defaultConfig(),
                registry,
                harnessService,
                repairPromptBuilder,
                repairStrategy,
                new TaskPlanningPolicy()
        );

        String result = orchestrator.process("session-a", "hello", event -> {});

        assertEquals("done", result);
        assertNotNull(observedRunId.get());
        TaskHarnessRun run = harnessService.getRun(observedRunId.get());
        assertNotNull(run);
        assertEquals("session-a", run.getSessionId());
    }

    @Test
    void shouldNotForceImplicitBatchTaskIntoWorklistRepair() {
        AgentRegistry registry = mock(AgentRegistry.class);
        TaskHarnessService harnessService = new TaskHarnessService();
        TaskHarnessRepairPromptBuilder repairPromptBuilder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessRepairStrategy repairStrategy = new TaskHarnessRepairStrategy(Config.defaultConfig());
        AgentLoop loop = mock(AgentLoop.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> repairInput = new AtomicReference<>();

        when(registry.getOrCreateAgent(any(AgentRole.class), anyString())).thenReturn(loop);
        when(loop.process(anyString(), anyString(), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    int call = calls.incrementAndGet();
                    String runId = AgentExecutionContext.getCurrentRunId();
                    if (call == 1) {
                        return "我会先枚举文件，然后逐个处理。";
                    }

                    repairInput.compareAndSet(null, invocation.getArgument(1));
                    Consumer<ExecutionEvent> callback = invocation.getArgument(2);
                    harnessService.planSubtask(
                            runId,
                            "file-a.pdf",
                            "file-a.pdf",
                            Map.of("source", "test"),
                            callback
                    );
                    harnessService.completeSubtask(
                            runId,
                            "file-a.pdf",
                            "checked",
                            true,
                            Map.of(),
                            callback
                    );
                    return "最终汇总：总文件数 1，已通过数量 1，未通过数量 0，失败数量 0。";
                });

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                Config.defaultConfig(),
                registry,
                harnessService,
                repairPromptBuilder,
                repairStrategy,
                new TaskPlanningPolicy()
        );

        String result = orchestrator.process("session-a", "请批量审查目录 D:\\DOC\\招生 下的所有PDF文件", event -> {});

        assertTrue(calls.get() >= 1);
        assertFalse(result.contains("WORKLIST_NOT_PLANNED"));
        if (repairInput.get() != null) {
            assertFalse(repairInput.get().contains("WORKLIST_NOT_PLANNED"));
        }
    }

    @Test
    void shouldContinueExplicitWorklistTextThroughPlanContract() {
        AgentRegistry registry = mock(AgentRegistry.class);
        TaskHarnessService harnessService = new TaskHarnessService();
        TaskHarnessRepairPromptBuilder repairPromptBuilder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessRepairStrategy repairStrategy = new TaskHarnessRepairStrategy(Config.defaultConfig());
        AgentLoop loop = mock(AgentLoop.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> continueInput = new AtomicReference<>();

        when(registry.getOrCreateAgent(any(AgentRole.class), anyString())).thenReturn(loop);
        when(loop.process(anyString(), anyString(), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    int call = calls.incrementAndGet();
                    String runId = AgentExecutionContext.getCurrentRunId();
                    if (call == 1) {
                        return "我会先枚举文件，然后逐个处理。";
                    }

                    continueInput.compareAndSet(null, invocation.getArgument(1));
                    Consumer<ExecutionEvent> callback = invocation.getArgument(2);
                    harnessService.planSubtask(
                            runId,
                            "file-a.pdf",
                            "file-a.pdf",
                            Map.of("source", "test"),
                            callback
                    );
                    harnessService.completeSubtask(
                            runId,
                            "file-a.pdf",
                            "checked",
                            true,
                            Map.of(),
                            callback
                    );
                    return "最终汇总：总文件数 1，已通过数量 1，未通过数量 0，失败数量 0。";
                });

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                Config.defaultConfig(),
                registry,
                harnessService,
                repairPromptBuilder,
                repairStrategy,
                new TaskPlanningPolicy()
        );

        String result = orchestrator.process("session-a", "请批量审查目录 D:\\DOC\\招生 下的所有PDF文件，使用 subtasks 登记 worklist 后逐个处理", event -> {});

        assertTrue(calls.get() >= 2);
        assertNotNull(continueInput.get());
        assertFalse(continueInput.get().contains("WORKLIST_NOT_PLANNED"));
        assertTrue(result.contains("最终汇总"));
    }

    @Test
    void shouldNotForceParentWorklistContractInsideChildHarnessRun() {
        AgentRegistry registry = mock(AgentRegistry.class);
        TaskHarnessService harnessService = new TaskHarnessService();
        TaskHarnessRepairPromptBuilder repairPromptBuilder = new TaskHarnessRepairPromptBuilder();
        TaskHarnessRepairStrategy repairStrategy = new TaskHarnessRepairStrategy(Config.defaultConfig());
        AgentLoop loop = mock(AgentLoop.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> childInput = new AtomicReference<>();
        AtomicReference<String> childRunId = new AtomicReference<>();

        when(registry.getOrCreateAgent(any(AgentRole.class), anyString())).thenReturn(loop);
        when(loop.process(anyString(), anyString(), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    childInput.set(invocation.getArgument(1));
                    childRunId.set(AgentExecutionContext.getCurrentRunId());
                    return "已完成当前子任务。";
                });

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                Config.defaultConfig(),
                registry,
                harnessService,
                repairPromptBuilder,
                repairStrategy,
                new TaskPlanningPolicy()
        );

        AgentExecutionContext.setCurrentContext(new AgentExecutionContext.ExecutionScope(
                "web:parent",
                null,
                "run-parent",
                null,
                "assistant",
                "Assistant",
                null
        ));

        String result = orchestrator.process(
                "spawn-child",
                "请批量审查目录 D:\\DOC\\招生 下的所有PDF文件，但当前子任务只处理 file-a.pdf",
                event -> {}
        );

        assertTrue(calls.get() >= 1);
        assertTrue(result.contains("已完成当前子任务"));
        assertNotNull(childInput.get());
        assertFalse(childInput.get().contains("This is a worklist task with independent items."));
        assertFalse(childInput.get().contains("subtasks(action='plan'"));
        TaskHarnessRun childRun = harnessService.getRun(childRunId.get());
        assertNotNull(childRun);
        assertFalse(childRun.getPlanningMode() == TaskPlanningMode.WORKLIST);
        assertFalse(childRun.getDoneDefinition().requiresWorklist());
    }
}

package io.jobclaw.agent.planning;

import io.jobclaw.agent.AgentLoop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPlanServiceTest {

    private final TaskPlanningPolicy fallbackPolicy = new TaskPlanningPolicy();
    private final AgentPlanService service = new AgentPlanService(fallbackPolicy);

    @Test
    void shouldUseAgentGeneratedPlanForNonDirectTasks() {
        AgentLoop agentLoop = mock(AgentLoop.class);
        when(agentLoop.callLLM(anyString(), any())).thenReturn("""
                {
                  "planningMode": "PHASED",
                  "deliveryType": "FILE_ARTIFACT",
                  "requiresWorklist": false,
                  "requiresFinalSummary": false,
                  "reason": "multi source report",
                  "steps": [
                    {"id": "inspect-inputs", "goal": "定位文件", "completion": "路径明确"},
                    {"id": "write-output", "goal": "生成 Word", "completion": "docx 已保存"}
                  ]
                }
                """);

        TaskPlan plan = service.plan(agentLoop, "总结 D:\\DOC\\招生 下的 PDF，写一个综述 Word");

        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertEquals("FILE_ARTIFACT", plan.doneDefinition().deliveryType().name());
        assertFalse(plan.doneDefinition().requiresWorklist());
        assertEquals("write-output", plan.steps().get(1).id());
        verify(agentLoop).callLLM(anyString(), isNull());
    }

    @Test
    void shouldNotCallAgentPlannerForDirectTasks() {
        AgentLoop agentLoop = mock(AgentLoop.class);

        TaskPlan plan = service.plan(agentLoop, "解释一下上下文压缩是什么意思");

        assertEquals(TaskPlanningMode.DIRECT, plan.planningMode());
        verify(agentLoop, never()).callLLM(anyString(), any());
    }

    @Test
    void shouldFallbackWhenAgentPlanIsInvalid() {
        AgentLoop agentLoop = mock(AgentLoop.class);
        when(agentLoop.callLLM(anyString(), any())).thenReturn("not json");

        TaskPlan plan = service.plan(agentLoop, "根据多个参考文件完善 D:\\work\\target.docx");

        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertEquals("PATCH", plan.doneDefinition().deliveryType().name());
    }
}

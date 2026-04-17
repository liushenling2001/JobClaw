package io.jobclaw.agent.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskPlanningPolicyTest {

    private final TaskPlanningPolicy policy = new TaskPlanningPolicy();

    @Test
    void shouldClassifyBatchFileReviewAsWorklist() {
        TaskPlanningDecision decision = policy.decide("请批量审查目录 D:\\DOC\\招生 下的所有 PDF 文件，并逐个检查每个文件");
        assertEquals(TaskPlanningMode.WORKLIST, decision.mode());
    }

    @Test
    void shouldClassifyDataReportTaskAsPhased() {
        TaskPlanningDecision decision = policy.decide("根据销售数据先分析，再总结，最后撰写报告");
        assertEquals(TaskPlanningMode.PHASED, decision.mode());
    }

    @Test
    void shouldClassifySimpleQuestionAsDirect() {
        TaskPlanningDecision decision = policy.decide("解释一下这个配置项是什么意思");
        assertEquals(TaskPlanningMode.DIRECT, decision.mode());
    }
}

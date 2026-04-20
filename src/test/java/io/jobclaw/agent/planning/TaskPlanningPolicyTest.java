package io.jobclaw.agent.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskPlanningPolicyTest {

    private final TaskPlanningPolicy policy = new TaskPlanningPolicy();

    @Test
    void shouldClassifyBatchFileReviewAsWorklist() {
        TaskPlan plan = policy.decide("请批量审查目录 D:\\DOC\\招生 下的所有 PDF 文件，并逐个检查每个文件");
        assertEquals(TaskPlanningMode.WORKLIST, plan.planningMode());
    }

    @Test
    void shouldClassifyDataReportTaskAsPhased() {
        TaskPlan plan = policy.decide("根据销售数据先分析，再总结，最后撰写报告");
        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
    }

    @Test
    void shouldClassifySimpleQuestionAsDirect() {
        TaskPlan plan = policy.decide("解释一下这个配置项是什么意思");
        assertEquals(TaskPlanningMode.DIRECT, plan.planningMode());
    }
}

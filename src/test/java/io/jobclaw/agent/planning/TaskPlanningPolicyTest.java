package io.jobclaw.agent.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskPlanningPolicyTest {

    private final TaskPlanningPolicy policy = new TaskPlanningPolicy();

    @Test
    void shouldNotForceBatchFileReviewIntoWorklistWithoutExplicitContract() {
        TaskPlan plan = policy.decide("请批量审查目录 D:\\DOC\\招生 下的所有 PDF 文件，并逐个检查每个文件");
        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertFalse(plan.doneDefinition().requiresWorklist());
    }

    @Test
    void shouldNotForceExplicitSubtasksRequestIntoWorklistInFallbackPolicy() {
        TaskPlan plan = policy.decide("请批量审查目录 D:\\DOC\\招生 下的所有 PDF 文件，使用 subtasks 登记 worklist 后逐个处理");
        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertFalse(plan.doneDefinition().requiresWorklist());
    }

    @Test
    void shouldClassifyDataReportTaskAsPhased() {
        TaskPlan plan = policy.decide("根据销售数据先分析，再总结，最后撰写报告");
        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertFalse(plan.steps().isEmpty());
    }

    @Test
    void shouldCreatePlanForSourceBasedFileImprovement() {
        TaskPlan plan = policy.decide("根据多个参考文件完善 D:\\work\\target.docx");
        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertEquals("PATCH", plan.doneDefinition().deliveryType().name());
        assertEquals("inspect-inputs", plan.steps().get(0).id());
        assertEquals("update-target", plan.steps().get(3).id());
    }

    @Test
    void shouldKeepArtifactDirectoryFromTaskContract() {
        TaskPlan plan = policy.decide("总结“D:\\DOC\\招生” 目录下所有PDF文件，形成不少于3000字的摘要，保存为word文件，并存在当前目录。");
        assertEquals(TaskPlanningMode.PHASED, plan.planningMode());
        assertEquals("FILE_ARTIFACT", plan.doneDefinition().deliveryType().name());
        assertEquals("D:\\DOC\\招生", plan.doneDefinition().requiredArtifactDirectories().get(0));
    }

    @Test
    void shouldClassifySimpleQuestionAsDirect() {
        TaskPlan plan = policy.decide("解释一下这个配置项是什么意思");
        assertEquals(TaskPlanningMode.DIRECT, plan.planningMode());
    }
}

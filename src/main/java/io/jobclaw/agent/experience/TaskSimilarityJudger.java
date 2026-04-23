package io.jobclaw.agent.experience;

import io.jobclaw.agent.completion.DeliveryType;
import io.jobclaw.agent.planning.TaskPlanningMode;

public interface TaskSimilarityJudger {

    boolean isSameTask(String currentTask,
                       String previousTask,
                       TaskPlanningMode planningMode,
                       DeliveryType deliveryType);
}

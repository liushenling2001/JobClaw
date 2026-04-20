package io.jobclaw.agent.workflow;

import java.util.List;

public interface WorkflowMemoryStore {

    List<WorkflowRecipe> list();

    void saveAll(List<WorkflowRecipe> recipes);
}

package io.jobclaw.agent.experience;

import java.util.List;

public interface ExperienceMemoryStore {

    List<ExperienceMemory> list();

    void saveAll(List<ExperienceMemory> memories);
}

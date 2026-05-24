package io.jobclaw.agent.experience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceMemoryServiceTest {

    @Test
    void shouldRecordHitsAndDowngradeLastInjectedMemoriesOnUserCorrection() {
        ExperienceMemory memory = memory("exp-a", 0.8);
        InMemoryExperienceMemoryStore store = new InMemoryExperienceMemoryStore(List.of(memory));
        ExperienceMemoryService service = new ExperienceMemoryService(store);

        service.recordInjected("session-a", List.of("exp-a"));
        ExperienceMemory afterHit = service.findById("exp-a").orElseThrow();
        assertEquals(1, afterHit.getHitCount());
        assertNotNull(afterHit.getLastHitAt());

        service.markLastInjectedContradictedIfCorrection("session-a", "错了，不是这个文件夹，不要按旧经验");
        ExperienceMemory afterCorrection = service.findById("exp-a").orElseThrow();
        assertEquals(1, afterCorrection.getContradictionCount());
        assertTrue(afterCorrection.getConfidence() < 0.8);
        assertNotNull(afterCorrection.getLastContradictedAt());
    }

    @Test
    void shouldRespectPinnedAndForgottenUserState() {
        ExperienceMemory memory = memory("exp-a", 0.8);
        InMemoryExperienceMemoryStore store = new InMemoryExperienceMemoryStore(List.of(memory));
        ExperienceMemoryService service = new ExperienceMemoryService(store);

        ExperienceMemory pinned = service.pin("exp-a").orElseThrow();
        assertEquals(ExperienceMemoryUserState.PINNED, pinned.getUserState());
        assertEquals(ExperienceMemoryStatus.ACTIVE, pinned.getStatus());

        service.markContradicted(List.of("exp-a"));
        ExperienceMemory stillPinned = service.findById("exp-a").orElseThrow();
        assertEquals(0, stillPinned.getContradictionCount());
        assertEquals(0.8, stillPinned.getConfidence());

        ExperienceMemory forgotten = service.forget("exp-a").orElseThrow();
        assertEquals(ExperienceMemoryUserState.FORGOTTEN, forgotten.getUserState());
        assertEquals(ExperienceMemoryStatus.DISABLED, forgotten.getStatus());
        assertTrue(service.listActive().isEmpty());
    }

    private static ExperienceMemory memory(String id, double confidence) {
        ExperienceMemory memory = new ExperienceMemory();
        memory.setId(id);
        memory.setStatus(ExperienceMemoryStatus.ACTIVE);
        memory.setType(ExperienceMemoryType.WORKFLOW_EXPERIENCE);
        memory.setTitle("folder cleanup");
        memory.setMethodGuidance("Confirm current target before cleanup.");
        memory.setConfidence(confidence);
        return memory;
    }

    private static class InMemoryExperienceMemoryStore implements ExperienceMemoryStore {
        private List<ExperienceMemory> memories;

        private InMemoryExperienceMemoryStore(List<ExperienceMemory> memories) {
            this.memories = new ArrayList<>(memories);
        }

        @Override
        public List<ExperienceMemory> list() {
            return memories;
        }

        @Override
        public void saveAll(List<ExperienceMemory> memories) {
            this.memories = new ArrayList<>(memories);
        }
    }
}

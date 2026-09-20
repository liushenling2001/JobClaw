package io.jobclaw.agent.experience;

import io.jobclaw.agent.evolution.MemoryStore;
import io.jobclaw.agent.learning.LearningCandidate;
import io.jobclaw.agent.learning.LearningCandidateType;
import io.jobclaw.config.ExperienceConfig;
import io.jobclaw.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperienceMemoryRetrieverTest {
    @TempDir Path root;

    @Test
    void highConfidenceAndPinningCannotFillWithUnrelatedExperience() {
        var memory = memory("a", "cleanup", "confirm directory contents");
        memory.setUserState(ExperienceMemoryUserState.PINNED);
        memory.setConfidence(1);
        var store = new FileExperienceMemoryStore(root.toString());
        store.saveAll(List.of(memory));
        var config = new ExperienceConfig();
        config.setConservativeMemoryMatching(false);
        var retriever = new ExperienceMemoryRetriever(new ExperienceMemoryService(store), config);
        assertTrue(retriever.retrieve("s", "你好").isEmpty());
        assertTrue(retriever.retrieve("s", "paint window magenta").isEmpty());
        assertEquals(1, retriever.retrieve("s", "confirm directory").size());
    }

    @Test
    void renderedEntriesAloneCountAsInjectedAndEmptyPromptStaysEmpty() {
        var store = new FileExperienceMemoryStore(root.toString());
        store.saveAll(List.of(memory("big", "cleanup", "directory ".repeat(300)),
                memory("small", "cleanup", "confirm directory")));
        var service = new ExperienceMemoryService(store);
        var config = new ExperienceConfig();
        config.setMaxInjectedChars(900);
        var retriever = new ExperienceMemoryRetriever(service, config);
        var big = new ExperienceMemoryMatch("big", ExperienceMemoryType.WORKFLOW_EXPERIENCE, "big",
                "directory ".repeat(300), 1, .9, false);
        var small = new ExperienceMemoryMatch("small", ExperienceMemoryType.WORKFLOW_EXPERIENCE, "small",
                "confirm directory", 1, .9, false);
        var prompt = retriever.preparePrompt(List.of(big, small));
        assertTrue(prompt.text().length() <= 900);
        assertEquals(List.of(small), prompt.matches());
        retriever.recordInjected("s", prompt.matches());
        assertEquals(0, service.findById("big").orElseThrow().getHitCount());
        assertEquals(1, service.findById("small").orElseThrow().getHitCount());
        assertEquals("", retriever.preparePrompt(List.of(big)).text());
    }

    @Test
    void projectExperienceCannotCrossWorkspacesEvenWhenPinned() {
        var registry = mock(WorkspaceRegistry.class);
        when(registry.resolveWorkingDirectory("a")).thenReturn(root.resolve("a").toString());
        when(registry.resolveWorkingDirectory("b")).thenReturn(root.resolve("b").toString());
        var memories = new MemoryStore(root.toString(), registry);
        var experience = memory("a", "cleanup", "confirm directory");
        experience.setUserState(ExperienceMemoryUserState.PINNED);
        experience.setMetadata(Map.of("scope", "project", "workspace", root.resolve("a").toString()));
        var store = new FileExperienceMemoryStore(root.resolve("experience").toString());
        store.saveAll(List.of(experience));
        var config = new ExperienceConfig();
        config.setConservativeMemoryMatching(false);
        var retriever = new ExperienceMemoryRetriever(new ExperienceMemoryService(store, memories), config, memories);
        assertEquals(1, retriever.retrieve("a", "confirm directory").size());
        assertTrue(retriever.retrieve("b", "confirm directory").isEmpty());
    }

    @Test
    void acceptedCandidateRetainsWorkspaceAndObjectType() {
        var registry = mock(WorkspaceRegistry.class);
        when(registry.resolveWorkingDirectory("a")).thenReturn(root.resolve("a").toString());
        var memories = new MemoryStore(root.toString(), registry);
        var service = new ExperienceMemoryService(new FileExperienceMemoryStore(root.resolve("experience").toString()), memories);
        var candidate = new LearningCandidate();
        candidate.setId("c1");
        candidate.setSessionId("a");
        candidate.setType(LearningCandidateType.WORKFLOW);
        candidate.setTaskInput("clean directory");
        candidate.setProposal("confirm directory before cleanup");
        var accepted = service.applyAcceptedCandidate(candidate).orElseThrow();
        assertEquals(memories.scopeFor("a").workspace(), accepted.getMetadata().get("workspace"));
        assertEquals("project", accepted.getMetadata().get("scope"));
        assertEquals("folder", accepted.getMetadata().get("objectType"));
    }

    private ExperienceMemory memory(String id, String task, String guidance) {
        var memory = new ExperienceMemory();
        memory.setId(id);
        memory.setTitle(task);
        memory.setTaskPattern(task);
        memory.setType(ExperienceMemoryType.WORKFLOW_EXPERIENCE);
        memory.setMethodGuidance(guidance);
        memory.setConfidence(.9);
        return memory;
    }
}

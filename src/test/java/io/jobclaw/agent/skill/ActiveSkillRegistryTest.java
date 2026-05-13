package io.jobclaw.agent.skill;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveSkillRegistryTest {

    @Test
    void shouldExtractRuntimeFrameOnly() {
        String content = """
                # Skill

                Intro.

                ## Runtime Frame

                - Keep the current input directory from this run.
                - Continue from manifest status.

                ## Full Workflow

                Long instructions.
                """;

        String frame = ActiveSkillRegistry.extractRuntimeFrame(content);

        assertThat(frame)
                .contains("Keep the current input directory")
                .contains("Continue from manifest status")
                .doesNotContain("Full Workflow")
                .doesNotContain("Long instructions");
    }

    @Test
    void shouldStoreAndFormatCurrentRunFrame() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();

        registry.activate("session-a", "run-1", "batch", """
                # Skill
                ## Runtime Frame
                Use current-run parameters only.
                """, "E:\\skills\\batch");

        String promptFrame = registry.formatForPrompt("session-a", "run-1");

        assertThat(promptFrame)
                .contains("[[JOBCLAW_ACTIVE_SKILL_FRAME]]")
                .contains("Active skill for this run: batch")
                .contains("Use current-run parameters only")
                .contains("E:\\skills\\batch");
    }
}

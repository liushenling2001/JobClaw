package io.jobclaw.agent.skill;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import io.jobclaw.agent.manifest.ActiveManifestRegistry;

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

    @Test
    void shouldReadManagedRunnerParallelismFromSkillOnly() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();

        registry.activate("session-a", "run-1", "batch", """
                # Skill
                ## Managed Runtime
                mode: runner
                parallelism: 3
                frameworkWrites: item-json,jsonl,manifest
                itemResultPathTemplate: {{task.inputDir}}\\items\\{{item.safeId}}.json
                aggregatePathTemplate: {{artifactPath}}
                itemOutput: json_object
                allowedTools: read_file, read_word, context_ref
                ### Item Loop
                Process {{item.id}} only.
                """, "E:\\skills\\batch");

        assertThat(registry.hasManagedRunnerRuntime("session-a", "run-1")).isTrue();
        assertThat(registry.managedRunnerParallelism("session-a", "run-1")).isEqualTo(3);
        assertThat(registry.hasManagedRunnerContract("session-a", "run-1")).isTrue();
        assertThat(registry.managedRunnerItemOutput("session-a", "run-1")).isEqualTo("json_object");
        assertThat(registry.managedRunnerAllowedTools("session-a", "run-1"))
                .containsExactly("read_file", "read_word", "context_ref");
    }

    @Test
    void shouldReportMissingManagedRunnerContractFields() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();

        registry.activate("session-a", "run-1", "batch", """
                # Skill
                ## Managed Runtime
                mode: runner
                ### Item Loop
                Process {{item.id}} only.
                """, "E:\\skills\\batch");

        assertThat(registry.hasManagedRunnerRuntime("session-a", "run-1")).isTrue();
        assertThat(registry.hasManagedRunnerContract("session-a", "run-1")).isFalse();
        assertThat(registry.managedRunnerContractError("session-a", "run-1"))
                .contains("itemOutput");
    }

    @Test
    void shouldNotRequireItemOrAggregatePathsForContextRefOnlyRunner() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();

        registry.activate("session-a", "run-1", "notes", """
                # Skill
                ## Managed Runtime
                mode: runner
                resultSink: context_ref
                aggregateSink: none
                itemOutput: markdown
                ### Item Loop
                Return one markdown note for {{item.id}}.
                """, "E:\\skills\\notes");

        assertThat(registry.hasManagedRunnerContract("session-a", "run-1")).isTrue();
        assertThat(registry.managedRunnerResultSink("session-a", "run-1")).isEqualTo("context_ref");
        assertThat(registry.managedRunnerAggregateSink("session-a", "run-1")).isEqualTo("none");
        assertThat(registry.managedRunnerWritesItemFile("session-a", "run-1")).isFalse();
        assertThat(registry.managedRunnerWritesAggregate("session-a", "run-1")).isFalse();
    }

    @Test
    void shouldRequireItemPathOnlyWhenSkillAsksForItemFileSink() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();

        registry.activate("session-a", "run-1", "batch", """
                # Skill
                ## Managed Runtime
                mode: runner
                resultSink: both
                aggregateSink: jsonl
                aggregatePathTemplate: {{artifactPath}}
                itemOutput: json_object
                ### Item Loop
                Return one object for {{item.id}}.
                """, "E:\\skills\\batch");

        assertThat(registry.hasManagedRunnerContract("session-a", "run-1")).isFalse();
        assertThat(registry.managedRunnerContractError("session-a", "run-1"))
                .contains("itemResultPathTemplate")
                .doesNotContain("aggregatePathTemplate");
    }

    @Test
    void shouldRenderManagedItemPathFromGenericTaskKeyVariables() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        registry.activate("session-a", "run-1", "custom", """
                # Skill
                ## Managed Runtime
                mode: runner
                frameworkWrites: item,manifest
                itemResultPathTemplate: {{task.workspace}}\\items\\{{item.safeId}}.txt
                aggregatePathTemplate: {{artifactPath}}
                itemOutput: text
                ### Item Loop
                Process {{item.id}}.
                """, "E:\\skills\\custom");
        ActiveManifestRegistry.ActiveManifestItem item = new ActiveManifestRegistry.ActiveManifestItem(
                "a/b:c.txt", "a/b:c.txt", "running", "", "", "", "");
        ActiveManifestRegistry.ActiveManifestState state = new ActiveManifestRegistry.ActiveManifestState(
                "session-a",
                "run-1",
                "mf-1",
                "skill:custom|workspace=D:\\work\\case-1|kind=demo",
                "{}",
                "managed",
                "D:\\work\\case-1\\index.jsonl",
                "",
                "",
                false,
                item,
                List.of(item),
                null,
                List.of(),
                1,
                0,
                1,
                0,
                0,
                Instant.now()
        );

        assertThat(registry.renderManagedItemResultPath("session-a", "run-1", state))
                .isEqualTo("D:\\work\\case-1\\items\\a_b_c.txt.txt");
    }
}

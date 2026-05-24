package io.jobclaw.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSelectionPolicyTest {

    @Test
    void shouldSelectOnlyRelevantInstalledSkills() {
        SkillInfo summarize = skill("summarize", "Summarize documents and produce concise reports");
        SkillInfo weather = skill("weather", "Query weather forecast");
        SkillInfo github = skill("github", "Inspect GitHub repositories and pull requests");

        List<SkillInfo> selected = new SkillSelectionPolicy().selectInstalledSkills(
                List.of(summarize, weather, github),
                "请总结这个文档并生成报告",
                5
        );

        assertEquals(1, selected.size());
        assertEquals("summarize", selected.get(0).getName());
    }

    @Test
    void shouldReturnEmptyWhenNoSkillMatches() {
        SkillInfo weather = skill("weather", "Query weather forecast");

        List<SkillInfo> selected = new SkillSelectionPolicy().selectInstalledSkills(
                List.of(weather),
                "计算这个表格中的同比增长率",
                5
        );

        assertTrue(selected.isEmpty());
    }

    private SkillInfo skill(String name, String description) {
        SkillInfo info = new SkillInfo();
        info.setName(name);
        info.setDescription(description);
        info.setSource("test");
        return info;
    }
}

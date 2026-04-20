package io.jobclaw.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsLoaderBuiltinExperienceReviewTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExposeExperienceReviewAsBuiltinSkill() {
        SkillsLoader loader = new SkillsLoader(tempDir.toString(), null, null);

        var skills = loader.listSkills();

        assertTrue(skills.stream().anyMatch(skill ->
                "experience-review".equals(skill.getName()) && "builtin".equals(skill.getSource())));
    }

    @Test
    void shouldLoadExperienceReviewSkillContent() {
        SkillsLoader loader = new SkillsLoader(tempDir.toString(), null, null);

        String content = loader.loadSkill("experience-review");

        assertNotNull(content);
        assertTrue(content.contains("Experience Review"));
        assertTrue(content.contains("negativeLessons"));
    }
}

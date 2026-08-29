package io.jobclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApplicationToolLimitConfigTest {

    @Test
    void shouldLeaveToolCallCountsUnlimitedForLongRunningTasks() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertEquals("-1", properties.getProperty("spring.ai.tools.limits.max-calls-per-tool-default"));
        assertEquals("-1", properties.getProperty("spring.ai.tools.limits.max-total-tool-calls"));
        assertNull(properties.getProperty("spring.ai.tools.limits.max-calls-per-tool.context_ref"));
    }
}

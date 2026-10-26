package io.jobclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadLimitsTest {

    @Test
    void applicationConfigAllowsTenMiBFilesAndFiftyMiBRequests() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        MultipartProperties properties = Binder.get(environment)
                .bind("spring.servlet.multipart", Bindable.of(MultipartProperties.class)).get();
        var config = properties.createMultipartConfig();
        assertEquals(10L * 1024 * 1024, config.getMaxFileSize());
        assertEquals(50L * 1024 * 1024, config.getMaxRequestSize());
    }
}

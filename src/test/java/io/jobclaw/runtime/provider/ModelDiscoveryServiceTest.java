package io.jobclaw.runtime.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDiscoveryServiceTest {

    private final Config config = Config.defaultConfig();
    private final ModelDiscoveryService service = new ModelDiscoveryService(config, new ObjectMapper());

    @Test
    void springSelectsTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Config.class, Config::defaultConfig);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ModelDiscoveryService.class);
            context.refresh();

            assertThat(context.getBean(ModelDiscoveryService.class)).isNotNull();
        }
    }

    @Test
    void parsesOpenAiCompatibleModelList() throws Exception {
        List<ModelDiscoveryService.DiscoveredModel> models = service.parseModels("""
                {"data":[
                  {"id":"qwen-2","owned_by":"local"},
                  {"id":"qwen-1","owned_by":"local"},
                  {"id":"qwen-1","owned_by":"duplicate"}
                ]}
                """);

        assertThat(models).extracting(ModelDiscoveryService.DiscoveredModel::id)
                .containsExactly("qwen-1", "qwen-2");
        assertThat(models.getFirst().ownedBy()).isEqualTo("local");
    }

    @Test
    void parsesOllamaNativeModelList() throws Exception {
        List<ModelDiscoveryService.DiscoveredModel> models = service.parseModels("""
                {"models":[
                  {"name":"qwen3:8b","model":"qwen3:8b"},
                  {"name":"gemma3:4b","model":"gemma3:4b"}
                ]}
                """);

        assertThat(models).extracting(ModelDiscoveryService.DiscoveredModel::id)
                .containsExactly("gemma3:4b", "qwen3:8b");
    }

    @Test
    void parsesGeminiModelsAndStripsResourcePrefix() throws Exception {
        List<ModelDiscoveryService.DiscoveredModel> models = service.parseModels("""
                {"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash"}]}
                """);

        assertThat(models).containsExactly(
                new ModelDiscoveryService.DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash", "")
        );
    }

    @Test
    void buildsCompatibleAndOllamaFallbackEndpoints() {
        assertThat(service.buildEndpoint("openai", "http://localhost:8000/v1/", false))
                .isEqualTo("http://localhost:8000/v1/models");
        assertThat(service.buildEndpoint("ollama", "http://localhost:11434/v1", true))
                .isEqualTo("http://localhost:11434/api/tags");
    }
}

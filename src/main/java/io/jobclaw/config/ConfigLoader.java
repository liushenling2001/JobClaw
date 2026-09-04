package io.jobclaw.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * JobClaw 配置加载器
 */
@Component
public class ConfigLoader {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String CONFIG_DIR = ".jobclaw";
    private static final String CONFIG_FILE = "config.json";
    private static final String HOME_PREFIX = "~";

    private static Dotenv dotenv = null;

    public static Config load() throws IOException {
        return load(getConfigPath());
    }

    public static Config load(String path) throws IOException {
        Config config = loadFromFile(path);
        applyEnvironmentOverrides(config);
        ModelRuntimeConfig.normalizeDefinitions(config);
        printLoadedConfigSummary(path, config);
        return config;
    }

    private static Config loadFromFile(String path) throws IOException {
        File configFile = new File(path);
        if (!configFile.exists()) {
            System.out.println();
            System.out.println("ℹ️  配置文件不存在，将使用默认配置");
            System.out.println();
            return Config.defaultConfig();
        }
        
        try {
            String content = Files.readString(configFile.toPath());
            Config config = objectMapper.readValue(content, Config.class);
            
            // 验证加载的配置
            if (config.getAgent() == null) {
                config.setAgent(new AgentConfig());
            }
            if (config.getProviders() == null) {
                config.setProviders(new ProvidersConfig());
            }
            if (config.getTools() == null) {
                config.setTools(new ToolsConfig());
            }
            if (config.getExperience() == null) {
                config.setExperience(new ExperienceConfig());
            }
            if (config.getGateway() == null) {
                config.setGateway(new GatewayConfig());
            }
            if (config.getMcpServers() == null) {
                config.setMcpServers(new MCPServersConfig());
            }
            
            return config;
        } catch (Exception e) {
            System.err.println();
            System.err.println("⚠️  配置文件加载失败：" + e.getMessage());
            System.err.println();
            System.err.println("可能原因：");
            System.err.println("  • JSON 格式错误（缺少逗号、引号等）");
            System.err.println("  • 配置文件编码问题");
            System.err.println("  • 配置文件权限问题");
            System.err.println();
            System.err.println("建议：");
            System.err.println("  1. 检查配置文件 JSON 格式：cat " + path);
            System.err.println("  2. 使用 JSON 验证工具：https://jsonlint.com/");
            System.err.println("  3. 重新生成配置：jobclaw onboard");
            System.err.println();
            System.err.println("配置文件存在但无法解析，启动已中止，避免退回默认 provider 造成误判。");
            System.err.println();
            throw new IOException("配置文件加载失败：" + path, e);
        }
    }

    public static void save(String path, Config config) throws IOException {
        File configFile = new File(path);
        ensureParentDirectory(configFile);
        String json = serialize(config);
        Files.writeString(configFile.toPath(), json);
    }

    /** Creates a minimal default configuration without overwriting an existing file. */
    public static Config createInitial(String path) throws IOException {
        File configFile = new File(path);
        ensureParentDirectory(configFile);
        Config config = Config.defaultConfig();
        Files.writeString(configFile.toPath(), serialize(config), StandardOpenOption.CREATE_NEW);
        return config;
    }

    private static String serialize(Config config) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(toPersistentTree(config));
    }

    static ObjectNode toPersistentTree(Config config) {
        Config actualConfig = config != null ? config : Config.defaultConfig();
        ModelRuntimeConfig.normalizeDefinitions(actualConfig);

        ObjectNode actual = objectMapper.valueToTree(actualConfig);
        ObjectNode defaults = objectMapper.valueToTree(Config.defaultConfig());
        ObjectNode differences = pruneDefaults(actual, defaults);
        ObjectNode result = objectMapper.createObjectNode();

        appendModels(result, actual, defaults);
        appendAgent(result, actual, differences);
        appendProviders(result, actual, defaults);

        Iterator<Map.Entry<String, JsonNode>> fields = differences.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!Set.of("models", "agent", "providers").contains(field.getKey())) {
                result.set(field.getKey(), field.getValue());
            }
        }
        return result;
    }

    private static void appendModels(ObjectNode result, ObjectNode actual, ObjectNode defaults) {
        ObjectNode definitions = objectMapper.createObjectNode();
        JsonNode actualDefinitions = actual.path("models").path("definitions");
        JsonNode defaultDefinitions = defaults.path("models").path("definitions");
        String activeModel = actual.path("agent").path("model").asText("");
        Set<String> names = new LinkedHashSet<>();
        actualDefinitions.fieldNames().forEachRemaining(name -> {
            if (name.equals(activeModel) || !actualDefinitions.path(name).equals(defaultDefinitions.path(name))) {
                names.add(name);
            }
        });
        if (!activeModel.isBlank() && actualDefinitions.has(activeModel)) {
            names.add(activeModel);
        }
        for (String name : names) {
            ObjectNode definition = actualDefinitions.path(name).deepCopy();
            removeNullFields(definition);
            definitions.set(name, definition);
        }
        if (!definitions.isEmpty()) {
            ObjectNode models = objectMapper.createObjectNode();
            models.set("definitions", definitions);
            result.set("models", models);
        }
    }

    private static void appendAgent(ObjectNode result, ObjectNode actual, ObjectNode differences) {
        ObjectNode source = (ObjectNode) actual.path("agent");
        ObjectNode agent = differences.has("agent")
                ? differences.withObject("agent").deepCopy()
                : objectMapper.createObjectNode();
        copyField(source, agent, "workspace");
        copyField(source, agent, "model");
        copyField(source, agent, "provider");
        copyField(source, agent, "restrictToWorkspace");
        copyField(source, agent, "compactionTriggerPercentage");
        copyField(source, agent, "compactionRetainPercentage");
        result.set("agent", agent);
    }

    private static void appendProviders(ObjectNode result, ObjectNode actual, ObjectNode defaults) {
        ObjectNode providers = objectMapper.createObjectNode();
        JsonNode actualProviders = actual.path("providers");
        JsonNode defaultProviders = defaults.path("providers");
        String activeProvider = actual.path("agent").path("provider").asText("");
        actualProviders.fieldNames().forEachRemaining(name -> {
            JsonNode provider = actualProviders.path(name);
            if (name.equals(activeProvider) || !provider.equals(defaultProviders.path(name))) {
                ObjectNode persisted = provider.deepCopy();
                removeNullFields(persisted);
                providers.set(name, persisted);
            }
        });
        if (!providers.isEmpty()) {
            result.set("providers", providers);
        }
    }

    private static ObjectNode pruneDefaults(ObjectNode actual, ObjectNode defaults) {
        ObjectNode result = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = actual.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = pruneNode(field.getValue(), defaults.get(field.getKey()));
            if (value != null) {
                result.set(field.getKey(), value);
            }
        }
        return result;
    }

    private static JsonNode pruneNode(JsonNode actual, JsonNode defaults) {
        if (actual == null || actual.isNull()) {
            return defaults == null || defaults.isNull() ? null : actual;
        }
        if (actual.isObject()) {
            ObjectNode pruned = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = actual.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode defaultChild = defaults != null && defaults.isObject()
                        ? defaults.get(field.getKey())
                        : null;
                JsonNode child = pruneNode(field.getValue(), defaultChild);
                if (child != null) {
                    pruned.set(field.getKey(), child);
                }
            }
            return pruned.isEmpty() ? null : pruned;
        }
        return actual.equals(defaults) ? null : actual.deepCopy();
    }

    private static void copyField(ObjectNode source, ObjectNode target, String name) {
        if (source.has(name)) {
            target.set(name, source.get(name));
        }
    }

    private static void removeNullFields(ObjectNode node) {
        Set<String> nullFields = new LinkedHashSet<>();
        node.fields().forEachRemaining(field -> {
            if (field.getValue().isObject()) {
                removeNullFields((ObjectNode) field.getValue());
            }
            if (field.getValue() == null || field.getValue().isNull()) {
                nullFields.add(field.getKey());
            }
        });
        node.remove(nullFields);
    }

    private static void ensureParentDirectory(File file) {
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
    }

    public static String getConfigPath() {
        String explicitPath = System.getProperty("jobclaw.config-path");
        if (explicitPath == null || explicitPath.isBlank()) {
            explicitPath = System.getenv("JOBCLAW_CONFIG_PATH");
        }
        if (explicitPath != null && !explicitPath.isBlank()) {
            return expandHome(explicitPath.trim());
        }
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE).toString();
    }

    private static void printLoadedConfigSummary(String path, Config config) {
        String provider = config.getAgent() != null ? config.getAgent().getProvider() : "<null>";
        String model = config.getAgent() != null ? config.getAgent().getModel() : "<null>";
        boolean providerKeyConfigured = false;
        if (config.getProviders() != null && provider != null) {
            ProvidersConfig.ProviderConfig providerConfig = config.getProviderConfigByName(provider);
            providerKeyConfigured = providerConfig != null
                    && providerConfig.getApiKey() != null
                    && !providerConfig.getApiKey().isBlank();
        }
        System.out.println("JobClaw config loaded: path=" + new File(path).getAbsolutePath()
                + ", agent.provider=" + provider
                + ", agent.model=" + model
                + ", providerApiKeyConfigured=" + providerKeyConfigured);
    }

    public static String expandHome(String path) {
        if (path == null || path.isEmpty() || !path.startsWith(HOME_PREFIX)) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (path.length() == 1) {
            return home;
        }
        if (path.charAt(1) == '/' || path.charAt(1) == '\\') {
            return home + path.substring(1);
        }
        return path;
    }

    private static void applyEnvironmentOverrides(Config config) {
        loadDotEnv();
        applyAgentOverrides(config);
        applyChannelOverrides(config);
        applyProviderOverrides(config);
        applyToolsOverrides(config);
        applySecurityOverrides(config);
    }

    private static void loadDotEnv() {
        try {
            dotenv = Dotenv.configure()
                    .directory(".")
                    .ignoreIfMissing()
                    .load();
        } catch (Exception e) {
            // Ignore if .env doesn't exist
        }
    }

    private static void applyAgentOverrides(Config config) {
        applyStringOverride("JOBCLAW_AGENT_WORKSPACE", config.getAgent()::setWorkspace);
        applyStringOverride("JOBCLAW_AGENT_MODEL", config.getAgent()::setModel);
        applyStringOverride("JOBCLAW_AGENT_REASONING_EFFORT", config.getAgent()::setReasoningEffort);
        applyIntOverride("JOBCLAW_AGENT_THINKING_TOKEN_BUDGET", config.getAgent()::setThinkingTokenBudget);
        applyDoubleOverride("JOBCLAW_AGENT_TEMPERATURE", config.getAgent()::setTemperature);
        applyIntOverride("JOBCLAW_AGENT_COMPACTION_TRIGGER_PERCENTAGE", config.getAgent()::setCompactionTriggerPercentage);
        applyIntOverride("JOBCLAW_AGENT_COMPACTION_RETAIN_PERCENTAGE", config.getAgent()::setCompactionRetainPercentage);
    }

    private static void applyChannelOverrides(Config config) {
        applyBooleanOverride("JOBCLAW_CHANNELS_TELEGRAM_ENABLED",
                config.getChannels().getTelegram()::setEnabled);
        applyStringOverride("JOBCLAW_CHANNELS_TELEGRAM_TOKEN",
                config.getChannels().getTelegram()::setToken);
        applyBooleanOverride("JOBCLAW_CHANNELS_DISCORD_ENABLED",
                config.getChannels().getDiscord()::setEnabled);
        applyStringOverride("JOBCLAW_CHANNELS_DISCORD_TOKEN",
                config.getChannels().getDiscord()::setToken);
    }

    private static void applyProviderOverrides(Config config) {
        applyStringOverride("JOBCLAW_PROVIDERS_OPENROUTER_API_KEY",
                config.getProviders().getOpenrouter()::setApiKey);
        applyStringOverride("JOBCLAW_PROVIDERS_ANTHROPIC_API_KEY",
                config.getProviders().getAnthropic()::setApiKey);
        applyStringOverride("JOBCLAW_PROVIDERS_OPENAI_API_KEY",
                config.getProviders().getOpenai()::setApiKey);
        applyStringOverride("JOBCLAW_PROVIDERS_ZHIPU_API_KEY",
                config.getProviders().getZhipu()::setApiKey);
        applyStringOverride("JOBCLAW_PROVIDERS_GEMINI_API_KEY",
                config.getProviders().getGemini()::setApiKey);
        applyStringOverride("JOBCLAW_PROVIDERS_DASHSCOPE_API_KEY",
                config.getProviders().getDashscope()::setApiKey);
    }

    private static void applyToolsOverrides(Config config) {
        // 确保 tools 配置不为 null
        if (config.getTools() == null) {
            config.setTools(new ToolsConfig());
        }
        if (config.getTools().getWeb() == null) {
            config.getTools().setWeb(new ToolsConfig.WebToolsConfig());
        }
        if (config.getTools().getWeb().getSearch() == null) {
            config.getTools().getWeb().setSearch(new ToolsConfig.WebSearchConfig());
        }
        applyStringOverride("JOBCLAW_TOOLS_WEB_SEARCH_API_KEY",
                config.getTools().getWeb().getSearch()::setApiKey);
    }

    private static void applySecurityOverrides(Config config) {
        // 确保 security 配置不为 null（如果需要）
        // 目前通过 Config 构造函数已初始化
    }

    private static void applyStringOverride(String envKey, Consumer<String> setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(value);
        }
    }

    private static void applyIntOverride(String envKey, IntConsumer setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(Integer.parseInt(value));
        }
    }

    private static void applyDoubleOverride(String envKey, DoubleConsumer setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(Double.parseDouble(value));
        }
    }

    private static void applyBooleanOverride(String envKey, Consumer<Boolean> setter) {
        String value = getEnv(envKey);
        if (value != null) {
            setter.accept(Boolean.parseBoolean(value));
        }
    }

    private static String getEnv(String key) {
        String value = System.getenv(key);
        if (value != null) {
            return value;
        }
        if (dotenv != null) {
            return dotenv.get(key);
        }
        return null;
    }
}

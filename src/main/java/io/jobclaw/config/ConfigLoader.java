package io.jobclaw.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
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
            if (config.getGateway() == null) {
                config.setGateway(new GatewayConfig());
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
            System.err.println("将使用默认配置继续启动...");
            System.err.println();
            return Config.defaultConfig();
        }
    }

    public static void save(String path, Config config) throws IOException {
        File configFile = new File(path);
        ensureParentDirectory(configFile);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        Files.writeString(configFile.toPath(), json);
    }

    private static void ensureParentDirectory(File file) {
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
    }

    public static String getConfigPath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE).toString();
    }

    public static String expandHome(String path) {
        if (path == null || path.isEmpty() || !path.startsWith(HOME_PREFIX)) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (path.length() == 1) {
            return home;
        }
        if (path.charAt(1) == '/') {
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
        applyIntOverride("JOBCLAW_AGENT_MAX_TOKENS", config.getAgent()::setMaxTokens);
        applyDoubleOverride("JOBCLAW_AGENT_TEMPERATURE", config.getAgent()::setTemperature);
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

package io.jobclaw.cli;

import io.jobclaw.SpringContext;
import io.jobclaw.agent.AgentLoop;
import io.jobclaw.bus.MessageBus;
import io.jobclaw.config.Config;
import io.jobclaw.config.ConfigLoader;
import io.jobclaw.providers.LLMProvider;
import io.jobclaw.session.SessionManager;

import java.io.File;
import java.nio.file.Paths;

/**
 * CLI command base class
 */
public abstract class CliCommand {

    protected static final String LOGO = "\uD83E\uDD9E";  // Crab emoji
    protected static final String VERSION = "0.1.0";

    /**
     * Get the command name
     */
    public abstract String name();

    /**
     * Get the command description
     */
    public abstract String description();

    /**
     * Execute the command with arguments
     * @param args Command line arguments
     * @return Exit code (0 for success, non-zero for failure)
     */
    public abstract int execute(String[] args) throws Exception;

    /**
     * Get AgentLoop bean from Spring context
     */
    protected AgentLoop getAgentLoop() {
        return SpringContext.getBean(AgentLoop.class);
    }

    /**
     * Get LLMProvider bean from Spring context
     */
    protected LLMProvider getProvider() {
        return SpringContext.getBean(LLMProvider.class);
    }

    /**
     * Get SessionManager bean from Spring context
     */
    protected SessionManager getSessionManager() {
        return SpringContext.getBean(SessionManager.class);
    }

    /**
     * Get MessageBus bean from Spring context
     */
    protected MessageBus getMessageBus() {
        return SpringContext.getBean(MessageBus.class);
    }

    /**
     * Get config file path
     */
    protected String getConfigPath() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".jobclaw", "config.json").toString();
    }

    /**
     * Load config file with friendly error messages
     * @return Config object, null on failure
     */
    protected Config loadConfig() {
        String configPath = getConfigPath();
        File configFile = new File(configPath);

        if (!configFile.exists()) {
            printConfigNotFoundError(configPath);
            return null;
        }

        try {
            return ConfigLoader.load(configPath);
        } catch (Exception e) {
            System.err.println();
            System.err.println(LOGO + " 配置文件加载失败");
            System.err.println();
            System.err.println("  原因：" + e.getMessage());
            System.err.println("  路径：" + configPath);
            System.err.println();
            System.err.println("请检查配置文件格式是否正确，或重新运行:");
            System.err.println("  jobclaw onboard");
            System.err.println();
            return null;
        }
    }

    /**
     * Print friendly config not found error
     */
    private void printConfigNotFoundError(String configPath) {
        System.err.println();
        System.err.println(LOGO + " 欢迎使用 JobClaw!");
        System.err.println();
        System.err.println("  看起来这是你第一次运行，需要先初始化配置。");
        System.err.println();
        System.err.println("  请运行以下命令开始:");
        System.err.println("    jobclaw onboard");
        System.err.println();
        System.err.println("  这将会:");
        System.err.println("    • 创建配置文件 " + configPath);
        System.err.println("    • 初始化工作空间目录");
        System.err.println("    • 生成模板文件");
        System.err.println();
    }

    /**
     * Print help information for this command
     */
    public abstract void printHelp();
}

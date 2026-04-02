package io.jobclaw.cli;

import io.jobclaw.agent.AgentLoop;
import io.jobclaw.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * Agent 命令，直接与 Agent 交互。
 */
@Component
public class AgentCommand extends CliCommand {

    private static final Logger logger = LoggerFactory.getLogger(AgentCommand.class);

    private static final String EXIT_COMMAND = "exit";       // 退出命令
    private static final String QUIT_COMMAND = "quit";       // 退出命令（别名）
    private static final String SESSION_PREFIX = "cli_";     // 会话 ID 前缀
    private static final String PROMPT_USER = "你：";         // 用户输入提示符
    private static final String PROMPT_SEPARATOR = ": ";     // Agent 响应提示符分隔符

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public String description() {
        return "直接与 Agent 交互";
    }

    @Override
    public int execute(String[] args) throws Exception {
        // 解析命令行参数
        CommandArgs cmdArgs = parseArguments(args);

        // 加载配置并创建 Agent
        Config config = loadConfig();
        if (config == null) {
            return 1;
        }

        AgentLoop agentLoop = createAndInitializeAgent(config);
        if (agentLoop == null) {
            return 1;
        }

        // 执行相应模式
        if (cmdArgs.hasMessage()) {
            executeSingleMessageMode(agentLoop, cmdArgs);
        } else {
            executeInteractiveMode(agentLoop, cmdArgs);
        }

        return 0;
    }

    /**
     * 解析命令行参数。
     *
     * @param args 命令行参数数组
     * @return 解析后的参数对象
     */
    private CommandArgs parseArguments(String[] args) {
        String message = "";
        String sessionKey = generateSessionKey();
        boolean debug = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--debug", "-d" -> {
                    debug = true;
                    System.out.println("🔍 Debug mode enabled");
                }
                case "-m", "--message" -> {
                    if (i + 1 < args.length) {
                        message = args[++i];
                    }
                }
                case "-s", "--session" -> {
                    if (i + 1 < args.length) {
                        sessionKey = args[++i];
                    }
                }
            }
        }

        return new CommandArgs(message, sessionKey, debug);
    }

    /**
     * 创建并初始化 Agent。
     *
     * @param config 配置对象
     * @return Agent 实例，失败时返回 null
     */
    private AgentLoop createAndInitializeAgent(Config config) {
        try {
            // 从 Spring 获取 AgentLoop
            AgentLoop agentLoop = getAgentLoop();
            if (agentLoop == null) {
                System.err.println("Error: AgentLoop bean not found in Spring context");
                return null;
            }

            System.out.println(LOGO + " Agent 已初始化");
            System.out.println("  • 模型：" + config.getAgent().getModel());
            System.out.println("  • 工作空间：" + config.getWorkspacePath());

            return agentLoop;
        } catch (Exception e) {
            System.err.println("Error creating AgentLoop: " + e.getMessage());
            return null;
        }
    }

    /**
     * 执行单条消息模式。
     *
     * @param agentLoop Agent 实例
     * @param cmdArgs 命令参数
     */
    private void executeSingleMessageMode(AgentLoop agentLoop, CommandArgs cmdArgs) throws Exception {
        System.out.println();
        System.out.print(LOGO + PROMPT_SEPARATOR);

        String response = agentLoop.process(cmdArgs.sessionKey, cmdArgs.message);
        System.out.println(response);
        System.out.println();
    }

    /**
     * 执行交互模式。
     *
     * @param agentLoop Agent 实例
     * @param cmdArgs 命令参数
     */
    private void executeInteractiveMode(AgentLoop agentLoop, CommandArgs cmdArgs) {
        System.out.println(LOGO + " 交互模式 (Ctrl+C to exit)");
        System.out.println();
        interactiveMode(agentLoop, cmdArgs.sessionKey);
    }

    /**
     * 交互模式主循环。
     *
     * @param agentLoop Agent 实例
     * @param sessionKey 会话键
     */
    private void interactiveMode(AgentLoop agentLoop, String sessionKey) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(PROMPT_USER);

            String input = readUserInput(scanner);
            if (input == null) {
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            if (isExitCommand(input)) {
                System.out.println("再见！");
                break;
            }

            processUserInput(agentLoop, input, sessionKey);
        }
    }

    /**
     * 读取用户输入。
     *
     * @param scanner 输入扫描器
     * @return 用户输入字符串，异常时返回 null
     */
    private String readUserInput(Scanner scanner) {
        try {
            return scanner.nextLine().trim();
        } catch (Exception e) {
            System.out.println("\n再见！");
            return null;
        }
    }

    /**
     * 判断是否为退出命令。
     *
     * @param input 用户输入
     * @return 是否为退出命令
     */
    private boolean isExitCommand(String input) {
        return EXIT_COMMAND.equals(input) || QUIT_COMMAND.equals(input);
    }

    /**
     * 处理用户输入并显示响应。
     *
     * @param agentLoop Agent 实例
     * @param input 用户输入
     * @param sessionKey 会话键
     */
    private void processUserInput(AgentLoop agentLoop, String input, String sessionKey) {
        try {
            System.out.println();
            System.out.print(LOGO + PROMPT_SEPARATOR);

            String response = agentLoop.process(sessionKey, input);
            System.out.println(response);
            System.out.println();

        } catch (Exception e) {
            System.err.println("错误：" + e.getMessage());
        }
    }

    @Override
    public void printHelp() {
        System.out.println(LOGO + " jobclaw agent - 直接与 Agent 交互");
        System.out.println();
        System.out.println("Usage: jobclaw agent [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -m, --message <text>    发送单条消息并退出");
        System.out.println("  -s, --session <key>     指定会话键（默认每次启动创建新会话）");
        System.out.println("  -d, --debug             启用调试模式");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  jobclaw agent                         # 交互模式");
        System.out.println("  jobclaw agent -m \"Hello!\"            # 单条消息");
        System.out.println("  jobclaw agent -s my-session -m \"Hi\"  # 指定会话（用于恢复历史对话）");
    }

    /**
     * 生成唯一的会话 ID。
     *
     * 格式：cli_yyyyMMdd_HHmmss
     *
     * @return 会话 ID
     */
    private String generateSessionKey() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return SESSION_PREFIX + LocalDateTime.now().format(formatter);
    }

    /**
     * 命令行参数封装类。
     */
    private record CommandArgs(String message, String sessionKey, boolean debug) {
        boolean hasMessage() {
            return !message.isEmpty();
        }
    }
}

package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具
 * 
 * 允许 AI Agent 执行系统命令，支持跨平台（Windows/Linux/macOS）
 * 
 * 核心功能：
 * - 执行 Shell 命令并捕获输出
 * - 支持自定义工作目录
 * - 超时控制
 * - 安全警告
 */
@Component
public class ExecTool {

    private static final int MAX_OUTPUT_LENGTH = 10000;         // 输出最大长度
    private static final long THREAD_JOIN_TIMEOUT_MS = 1000;    // 线程等待超时（毫秒）
    private static final int MAX_INLINE_SCRIPT_COMMAND_LENGTH = 2000;
    private static final int MAX_INLINE_SCRIPT_LINES = 8;
    private final Config config;

    public ExecTool(Config config) {
        this.config = config;
    }

    @Tool(name = "exec", description = "Execute a shell command and return output. WARNING: Use with caution!")
    public String execute(
        @ToolParam(description = "The shell command to execute") String command,
        @ToolParam(description = "Working directory (optional, defaults to current directory)") String workingDir,
        @ToolParam(description = "Timeout in seconds (optional, defaults to agent.toolCallTimeoutSeconds)") Integer timeout
    ) {
        if (command == null || command.isEmpty()) {
            return "Error: command is required";
        }
        if (looksLikeUnsafeInlineScript(command)) {
            return "Error: Inline script command is too large or complex for reliable shell execution. "
                    + "Write the script to a temporary .py/.ps1/.sh file with write_file first, then run that file with exec. "
                    + "This allows stderr, exit code, and repair to work reliably.";
        }

        // Resolve working directory
        String cwd = workingDir != null && !workingDir.isEmpty() 
            ? workingDir 
            : System.getProperty("user.dir");

        // Security warning
        System.out.println("[ExecTool] WARNING: Executing command without SecurityGuard: " + command);

        try {
            return executeCommand(command, cwd, timeout != null ? timeout : defaultTimeoutSeconds());
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    private int defaultTimeoutSeconds() {
        if (config == null || config.getAgent() == null || config.getAgent().getToolCallTimeoutSeconds() <= 0) {
            return 300;
        }
        return config.getAgent().getToolCallTimeoutSeconds();
    }

    private boolean looksLikeUnsafeInlineScript(String command) {
        String normalized = command == null ? "" : command.toLowerCase();
        long lines = normalized.lines().count();
        boolean multilineScript = lines > MAX_INLINE_SCRIPT_LINES
                && (normalized.contains("python")
                || normalized.contains("powershell")
                || normalized.contains("bash")
                || normalized.contains("node "));
        boolean heredoc = normalized.contains("<<") || normalized.contains("\neof") || normalized.contains("@'");
        return command.length() > MAX_INLINE_SCRIPT_COMMAND_LENGTH || multilineScript || heredoc;
    }

    /**
     * Execute command and capture output
     */
    private String executeCommand(String command, String cwd, int timeoutSeconds) throws Exception {
        // Build process
        Process process = buildProcess(command, cwd);

        // Capture output
        CommandOutput output = captureOutput(process);

        // Wait for process to complete
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        // Wait for output reader threads
        output.waitForThreads();

        // Handle timeout
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return "Error: Command timed out after " + timeoutSeconds + " seconds";
        }

        // Build result
        return buildResult(output, process.exitValue());
    }

    /**
     * Build command execution process
     */
    private Process buildProcess(String command, String cwd) throws Exception {
        String[] shellCmd = getShellCommand(command);

        ProcessBuilder pb = new ProcessBuilder(shellCmd);
        pb.directory(Paths.get(cwd).toFile());
        pb.redirectErrorStream(false);

        return pb.start();
    }

    /**
     * Get shell command for current OS
     */
    private String[] getShellCommand(String command) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new String[]{"cmd", "/c", command};
        } else {
            return new String[]{"sh", "-c", command};
        }
    }

    /**
     * Capture command output using separate threads
     */
    private CommandOutput captureOutput(Process process) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = createOutputThread(process.getInputStream(), stdout, "exec-stdout");
        Thread stderrThread = createOutputThread(process.getErrorStream(), stderr, "exec-stderr");

        stdoutThread.start();
        stderrThread.start();

        return new CommandOutput(stdout, stderr, stdoutThread, stderrThread);
    }

    /**
     * Create output reader thread
     */
    private Thread createOutputThread(InputStream inputStream, StringBuilder output, String threadName) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                System.out.println("[ExecTool] Output reader exception: " + e.getMessage());
            }
        }, threadName);
    }

    /**
     * Build execution result
     */
    private String buildResult(CommandOutput output, int exitCode) {
        String result = output.getStdout();

        // Add stderr
        if (!output.getStderr().isEmpty()) {
            result += "\nSTDERR:\n" + output.getStderr();
        }

        if (exitCode != 0) {
            result = "Error: Command exited with code " + exitCode
                    + (result.isBlank() ? "" : "\n" + result);
        }

        // Handle empty output
        if (result.isEmpty()) {
            result = "(no output)";
        }

        // Truncate if too long
        return truncateIfNeeded(result);
    }

    /**
     * Truncate overly long output
     */
    private String truncateIfNeeded(String result) {
        if (result.length() > MAX_OUTPUT_LENGTH) {
            int remaining = result.length() - MAX_OUTPUT_LENGTH;
            return result.substring(0, MAX_OUTPUT_LENGTH) 
                    + "\n... (truncated, " + remaining + " more characters)";
        }
        return result;
    }

    /**
     * Command output wrapper
     */
    private static class CommandOutput {
        private final StringBuilder stdout;
        private final StringBuilder stderr;
        private final Thread stdoutThread;
        private final Thread stderrThread;

        CommandOutput(StringBuilder stdout, StringBuilder stderr, 
                     Thread stdoutThread, Thread stderrThread) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdoutThread = stdoutThread;
            this.stderrThread = stderrThread;
        }

        String getStdout() {
            synchronized (stdout) {
                return stdout.toString();
            }
        }

        String getStderr() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }

        void waitForThreads() {
            try {
                stdoutThread.join(THREAD_JOIN_TIMEOUT_MS);
                stderrThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

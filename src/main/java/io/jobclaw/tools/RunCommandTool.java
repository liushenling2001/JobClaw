package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具
 *
 * 允许 AI Agent 执行系统命令，支持跨平台（Windows/Linux/macOS）
 *
 * 核心功能：
 * - 执行 Shell 命令并捕获输出
 * - 支持自定义工作目录（base-path）
 * - 超时控制
 * - 安全警告
 *
 * 典型用例：
 * - 执行技能脚本：run_command(command='python3 {base-path}/script.py arg1')
 * - 运行系统命令：run_command(command='ls -la', workingDir='/path/to/dir')
 */
@Component
public class RunCommandTool {

    private static final int MAX_OUTPUT_LENGTH = 10000;         // 输出最大长度
    private static final long THREAD_JOIN_TIMEOUT_MS = 1000;    // 线程等待超时（毫秒）
    private static final int MAX_INLINE_SCRIPT_COMMAND_LENGTH = 2000;
    private static final int MAX_INLINE_SCRIPT_LINES = 8;
    private static final Pattern SENSITIVE_FILE_READ_PATTERN = Pattern.compile(
            "(?i)\\b(type|cat|more|get-content|gc)\\b[^\\r\\n]*(^|[\\s\"'`=:/\\\\])"
                    + "(\\.env(?:\\.[\\w-]+)?|credentials\\.(?:json|ya?ml|toml|ini)|"
                    + "secrets?\\.(?:json|ya?ml|toml|ini)|id_rsa|id_ed25519)\\b"
    );
    private static final Pattern CD_COMMAND_PATTERN = Pattern.compile(
            "(?i)\\bcd(?:\\s+/d)?\\s+(?:\"([^\"]+)\"|'([^']+)'|([^&|\\r\\n]+))"
    );
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b([A-Z0-9_]*(?:API_KEY|TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE_KEY|ACCESS_KEY)[A-Z0-9_]*\\s*[:=]\\s*)([^\\s\"'`]+)"
    );
    private static final Pattern BEARER_SECRET_PATTERN = Pattern.compile("(?i)\\b(Bearer\\s+)([A-Za-z0-9._~+\\-/=]{12,})");
    private static final Pattern SK_SECRET_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9][A-Za-z0-9_\\-]{8,}\\b");
    private static final List<String> SKILL_PROTECTED_REFS = List.of(
            "skill.md",
            ".env",
            "scripts/",
            "scripts\\",
            "runtime/",
            "runtime\\",
            "assets/",
            "assets\\"
    );
    private static final Pattern SKILL_SOURCE_WRITE_VERB_PATTERN = Pattern.compile(
            "(?i)\\b(set-content|out-file|add-content|remove-item|move-item|copy-item|"
                    + "del|erase|ren|rename|rm|mv|cp)\\b"
    );
    private static final Pattern SKILL_PROTECTED_REDIRECT_PATTERN = Pattern.compile(
            "(?i)(>>|>)\\s*[^\\r\\n]*(skill\\.md|\\.env|scripts/|runtime/|assets/)"
    );
    private final Config config;

    public RunCommandTool(Config config) {
        this.config = config;
    }

    @Tool(name = "run_command", description = "Execute a shell command and return output. Use this tool when you need to: 1) Run system commands, 2) Execute scripts (Python, Bash, etc.), 3) Invoke skill scripts. For skill scripts, follow the skill instructions exactly, set workingDir to the base-path returned by skills(action='invoke'), and set timeout for long-running jobs.")
    public String execute(
        @ToolParam(description = "The shell command to execute. For skill scripts, use the command entrypoint documented by the skill instead of inventing or editing implementation files.") String command,
        @ToolParam(description = "Working directory (optional, defaults to current directory). Use the base-path from skills invoke for skill scripts") String workingDir,
        @ToolParam(description = "Timeout in seconds (optional, defaults to agent.toolCallTimeoutSeconds)") Integer timeout
    ) {
        if (command == null || command.isEmpty()) {
            return "Error: command is required";
        }
        if (looksLikeUnsafeInlineScript(command)) {
            return "Error: Inline script command is too large or complex for reliable shell execution. "
                    + "Write the script to a temporary .py/.ps1/.sh file with write_file first, then run that file with run_command. "
                    + "This allows stderr, exit code, and repair to work reliably.";
        }

        // Resolve working directory
        String cwd = workingDir != null && !workingDir.isEmpty() 
            ? workingDir 
            : System.getProperty("user.dir");

        String safetyError = validateCommandSafety(command, cwd);
        if (safetyError != null) {
            return safetyError;
        }

        // Security warning
        System.out.println("[RunCommandTool] WARNING: Executing command without SecurityGuard: " + command);

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

    private String validateCommandSafety(String command, String cwd) {
        Matcher sensitiveRead = SENSITIVE_FILE_READ_PATTERN.matcher(command);
        if (sensitiveRead.find()) {
            return "Error: Refusing to read sensitive credential file via shell command. "
                    + "Ask the user to configure the required value or provide a non-secret excerpt.";
        }

        Path skillRoot = findSkillRoot(cwd, command);
        if (skillRoot == null) {
            return null;
        }

        String ps1WrapperError = validateSkillPowerShellWrapper(command, skillRoot);
        if (ps1WrapperError != null) {
            return ps1WrapperError;
        }

        if (looksLikeSkillSourceMutation(command)) {
            return "Error: Refusing to modify skill implementation files from a runtime command. "
                    + "Use skills(action='edit') or an explicit skill-maintenance task when the user asks to edit the skill.";
        }

        return null;
    }

    private Path findSkillRoot(String cwd, String command) {
        Path rootFromWorkingDir = findSkillRoot(cwd);
        if (rootFromWorkingDir != null) {
            return rootFromWorkingDir;
        }
        return findSkillRootFromCommandDirectory(cwd, command);
    }

    private Path findSkillRoot(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return null;
        }

        try {
            return findSkillRoot(Paths.get(cwd).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path findSkillRoot(Path directory) {
        Path current = directory;
        while (current != null) {
            if (Files.isRegularFile(current.resolve("SKILL.md"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private Path findSkillRootFromCommandDirectory(String cwd, String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        Matcher matcher = CD_COMMAND_PATTERN.matcher(command);
        while (matcher.find()) {
            String target = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            Path resolved = resolveCommandDirectory(cwd, target);
            if (resolved == null) {
                continue;
            }
            Path skillRoot = findSkillRoot(resolved);
            if (skillRoot != null) {
                return skillRoot;
            }
        }
        return null;
    }

    private Path resolveCommandDirectory(String cwd, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String cleaned = target.trim();
        try {
            Path path = Paths.get(cleaned);
            if (!path.isAbsolute()) {
                path = Paths.get(cwd).resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String validateSkillPowerShellWrapper(String command, Path skillRoot) {
        if (!isWindows() || !command.toLowerCase(Locale.ROOT).contains(".ps1")) {
            return null;
        }

        for (Path directory : List.of(skillRoot, skillRoot.resolve("scripts"))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.list(directory)) {
                for (Path cmdWrapper : stream
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmd"))
                        .toList()) {
                    String baseName = stripExtension(cmdWrapper.getFileName().toString()).toLowerCase(Locale.ROOT);
                    if (command.toLowerCase(Locale.ROOT).contains(baseName + ".ps1")) {
                        return "Error: Refusing to execute a PowerShell skill script directly because a .cmd wrapper exists. "
                                + "Run '" + cmdWrapper.toAbsolutePath().normalize() + "' from the skill base-path instead.";
                    }
                }
            } catch (Exception ignored) {
                // Continue without wrapper validation if a directory cannot be listed.
            }
        }
        return null;
    }

    private boolean looksLikeSkillSourceMutation(String command) {
        String normalized = command.toLowerCase(Locale.ROOT).replace('\\', '/');
        boolean protectedReference = SKILL_PROTECTED_REFS.stream()
                .map(ref -> ref.toLowerCase(Locale.ROOT).replace('\\', '/'))
                .anyMatch(normalized::contains);
        return (protectedReference && SKILL_SOURCE_WRITE_VERB_PATTERN.matcher(normalized).find())
                || SKILL_PROTECTED_REDIRECT_PATTERN.matcher(normalized).find();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                System.out.println("[RunCommandTool] Output reader exception: " + e.getMessage());
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
        return truncateIfNeeded(redactSensitiveOutput(result));
    }

    private String redactSensitiveOutput(String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        String redacted = SECRET_ASSIGNMENT_PATTERN.matcher(result).replaceAll("$1[REDACTED_SECRET]");
        redacted = BEARER_SECRET_PATTERN.matcher(redacted).replaceAll("$1[REDACTED_SECRET]");
        redacted = SK_SECRET_PATTERN.matcher(redacted).replaceAll("[REDACTED_SECRET]");
        return redacted;
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

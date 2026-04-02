package io.jobclaw.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 安全守卫 - 工作空间沙箱和命令黑名单
 */
@Component
public class SecurityGuard {

    private static final Logger logger = LoggerFactory.getLogger(SecurityGuard.class);

    private final String workspace;
    private final boolean restrictToWorkspace;
    private final List<Pattern> commandBlacklist;
    private final List<Path> protectedPaths;

    public SecurityGuard() {
        this(Paths.get(System.getProperty("user.home"), ".jobclaw", "workspace").toString(), true);
    }

    public SecurityGuard(String workspace, boolean restrictToWorkspace) {
        this.workspace = normalizeWorkspacePath(workspace);
        this.restrictToWorkspace = restrictToWorkspace;
        this.commandBlacklist = buildDefaultCommandBlacklist();
        this.protectedPaths = buildDefaultProtectedPaths();

        logger.info("SecurityGuard initialized");
    }

    public SecurityGuard(String workspace, boolean restrictToWorkspace, List<String> customBlacklist) {
        this.workspace = normalizeWorkspacePath(workspace);
        this.restrictToWorkspace = restrictToWorkspace;
        this.commandBlacklist = buildCommandBlacklist(customBlacklist);
        this.protectedPaths = buildDefaultProtectedPaths();

        logger.info("SecurityGuard initialized with custom blacklist");
    }

    public String checkFilePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "File path is required";
        }

        try {
            Path resolvedPath = resolveRealPath(Paths.get(filePath));

            String protectedError = checkProtectedPath(resolvedPath, filePath);
            if (protectedError != null) {
                return protectedError;
            }

            if (!restrictToWorkspace) {
                return null;
            }

            Path workspacePath = resolveRealPath(Paths.get(workspace));

            if (!resolvedPath.startsWith(workspacePath)) {
                logger.warn("File path blocked (outside workspace)");
                return String.format(
                        "Access denied: Path '%s' is outside workspace '%s'",
                        filePath, workspace
                );
            }

            return null;

        } catch (Exception e) {
            logger.error("Error checking file path", e);
            return "Invalid file path: " + e.getMessage();
        }
    }

    private Path resolveRealPath(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath();

        if (absolutePath.toFile().exists()) {
            return absolutePath.toRealPath();
        }

        Path current = absolutePath;
        List<String> pendingParts = new ArrayList<>();

        while (current != null && !current.toFile().exists()) {
            pendingParts.add(0, current.getFileName().toString());
            current = current.getParent();
        }

        if (current == null) {
            return absolutePath.normalize();
        }

        Path realAncestor = current.toRealPath();
        Path result = realAncestor;
        for (String part : pendingParts) {
            result = result.resolve(part);
        }
        return result.normalize();
    }

    public String checkCommand(String command) {
        if (command == null || command.isEmpty()) {
            return "Command is required";
        }

        for (Pattern pattern : commandBlacklist) {
            if (pattern.matcher(command).find()) {
                logger.warn("Command blocked by blacklist");
                return String.format(
                        "Command blocked by safety guard (dangerous pattern detected): %s",
                        pattern.pattern()
                );
            }
        }

        return null;
    }

    public String checkWorkingDir(String workingDir) {
        if (!restrictToWorkspace) {
            return null;
        }

        if (workingDir == null || workingDir.isEmpty()) {
            return null;
        }

        return checkFilePath(workingDir);
    }

    public String getWorkspace() {
        return workspace;
    }

    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }

    private String checkProtectedPath(Path resolvedPath, String originalPath) {
        for (Path protectedPath : protectedPaths) {
            if (resolvedPath.equals(protectedPath)) {
                logger.warn("File path blocked (protected sensitive file)");
                return String.format(
                        "Access denied: '%s' is a protected sensitive file and cannot be read or modified",
                        originalPath
                );
            }
        }
        return null;
    }

    private List<Path> buildDefaultProtectedPaths() {
        String home = System.getProperty("user.home");
        List<Path> paths = new ArrayList<>();
        try {
            paths.add(resolveRealPath(Paths.get(home, ".jobclaw", "config.json")));
            paths.add(resolveRealPath(Paths.get(home, ".jobclaw", ".env")));
        } catch (IOException e) {
            paths.add(Paths.get(home, ".jobclaw", "config.json").toAbsolutePath().normalize());
            paths.add(Paths.get(home, ".jobclaw", ".env").toAbsolutePath().normalize());
        }
        return paths;
    }

    private String normalizeWorkspacePath(String path) {
        if (path == null || path.isEmpty()) {
            return System.getProperty("user.home") + "/.jobclaw/workspace";
        }

        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }

        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            logger.error("Failed to normalize workspace path", e);
            return path;
        }
    }

    private List<Pattern> buildDefaultCommandBlacklist() {
        List<String> defaultPatterns = List.of(
                "\\brm\\s+-[rf]{1,2}\\b",
                "\\bdel\\s+/[fq]\\b",
                "\\brmdir\\s+/s\\b",
                "\\b(format|mkfs|diskpart)\\b\\s",
                "\\bdd\\s+if=",
                ">\\s*/dev/sd[a-z]\\b",
                "\\b(shutdown|reboot|poweroff|halt)\\b",
                ":\\(\\)\\s*\\{.*\\};\\s*:",
                "\\b(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh|python|perl|ruby)",
                "\\b(sudo|su)\\s+",
                "\\bkillall\\s+-9\\b",
                "\\bpkill\\s+-9\\b",
                "\\bcrontab\\s+-r\\b",
                "\\bexport\\s+LD_PRELOAD\\b",
                "\\b(insmod|rmmod|modprobe)\\b",
                "\\.jobclaw[/\\\\]config\\.json",
                "\\.jobclaw[/\\\\]\\.env"
        );

        return buildCommandBlacklist(defaultPatterns);
    }

    private List<Pattern> buildCommandBlacklist(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                logger.error("Failed to compile blacklist pattern", e);
            }
        }
        return compiled;
    }

    public List<String> getBlacklistPatterns() {
        return commandBlacklist.stream()
                .map(Pattern::pattern)
                .toList();
    }
}

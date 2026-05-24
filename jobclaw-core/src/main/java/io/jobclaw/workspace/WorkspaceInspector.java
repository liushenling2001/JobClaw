package io.jobclaw.workspace;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Component
public class WorkspaceInspector {

    public WorkspaceContext inspect(String requestedCwd) {
        Path cwd = normalize(requestedCwd);
        String gitRoot = git(cwd, "rev-parse", "--show-toplevel");
        Path projectRoot = gitRoot != null && !gitRoot.isBlank()
                ? Paths.get(gitRoot).toAbsolutePath().normalize()
                : cwd;
        String branch = git(cwd, "branch", "--show-current");
        String status = git(cwd, "status", "--porcelain");
        return new WorkspaceContext(
                projectRoot.toString(),
                cwd.toString(),
                gitRoot,
                branch != null && !branch.isBlank() ? branch : null,
                status != null && !status.isBlank()
        );
    }

    private Path normalize(String requestedCwd) {
        Path cwd = requestedCwd == null || requestedCwd.isBlank()
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(requestedCwd);
        return cwd.toAbsolutePath().normalize();
    }

    private String git(Path cwd, String... args) {
        try {
            if (!Files.isDirectory(cwd)) {
                return null;
            }
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}

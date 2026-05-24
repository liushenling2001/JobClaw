package io.jobclaw.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill Installer - Install skills from GitHub repositories
 *
 * Provides the ability to clone skills from remote GitHub repositories to the local workspace.
 * Supports multiple repository formats and automatic error detection and handling.
 */
public class SkillsInstaller {

    private static final Logger logger = LoggerFactory.getLogger(SkillsInstaller.class);

    // GitHub base URL
    private static final String GITHUB_BASE_URL = "https://github.com/";

    // Workspace path
    private final String workspace;

    // Skills directory path
    private final String skillsDir;

    // Installing skills to prevent concurrent duplicate installations
    private final Map<String, Boolean> installing = new ConcurrentHashMap<>();

    /**
     * Create skill installer
     *
     * @param workspace Workspace path
     */
    public SkillsInstaller(String workspace) {
        this.workspace = workspace;
        this.skillsDir = Paths.get(workspace, "skills").toString();
    }

    /**
     * Install skill from GitHub repository
     *
     * @param repoSpecifier Repository specifier (owner/repo or full URL)
     * @return Installation result message
     * @throws Exception Installation errors
     */
    public String install(String repoSpecifier) throws Exception {
        // Parse repository information
        RepoInfo repoInfo = parseRepoSpecifier(repoSpecifier);
        String skillName = repoInfo.skillName;

        // Prevent duplicate installations
        if (installing.putIfAbsent(skillName, true) != null) {
            throw new Exception("Skill '" + skillName + "' is being installed, please wait...");
        }

        try {
            // Check if already installed
            Path targetPath = Paths.get(skillsDir, skillName);
            if (Files.exists(targetPath)) {
                throw new Exception("Skill '" + skillName + "' already exists. Please use 'skills remove " + skillName + "' to remove it first.");
            }

            // Check if git is available
            if (!isGitAvailable()) {
                throw new Exception("git command is not available. Please ensure git is installed and added to PATH.");
            }

            logger.info("Installing skill: repo={}, skill={}", repoInfo.repoUrl, skillName);

            // Create temporary directory
            Path tempDir = Files.createTempDirectory("jobclaw-skill-");

            try {
                // Clone repository
                cloneRepository(repoInfo.repoUrl, tempDir.toString());

                // Determine source directory (could be repo root or subdirectory)
                Path sourceDir = tempDir;
                if (repoInfo.subdir != null && !repoInfo.subdir.isEmpty()) {
                    sourceDir = tempDir.resolve(repoInfo.subdir);
                }

                // Verify SKILL.md exists in source directory
                Path skillFile = sourceDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    throw new Exception("SKILL.md file not found in repository. Please ensure this is a valid skill repository.");
                }

                // Ensure target directory exists
                Files.createDirectories(targetPath.getParent());

                // Copy skill files
                copyDirectory(sourceDir, targetPath);

                logger.info("Skill installed successfully: skill={}, path={}", skillName, targetPath);

                return "✓ Skill '" + skillName + "' installed successfully!";

            } finally {
                // Clean up temporary directory
                deleteDirectory(tempDir.toFile());
            }

        } finally {
            installing.remove(skillName);
        }
    }

    /**
     * Parse repository specifier
     *
     * @param specifier Repository specifier
     * @return Parsed repository info
     * @throws Exception Invalid format
     */
    private RepoInfo parseRepoSpecifier(String specifier) throws Exception {
        if (specifier == null || specifier.trim().isEmpty()) {
            throw new Exception("Repository specifier cannot be empty");
        }

        specifier = specifier.trim();

        RepoInfo info = new RepoInfo();

        // Handle full URL
        if (specifier.startsWith("https://") || specifier.startsWith("http://")) {
            info.repoUrl = specifier;
            String path = specifier.replaceFirst("^https?://github\\.com/", "");
            String[] parts = path.split("/");
            info.skillName = parts.length >= 2 ? parts[1].replace(".git", "") : parts[0];
        }
        // Handle SSH URL
        else if (specifier.startsWith("git@")) {
            info.repoUrl = specifier;
            String path = specifier.replaceFirst("^git@github\\.com:", "");
            String[] parts = path.split("/");
            info.skillName = parts.length >= 1 ? parts[0].replace(".git", "") : "unknown";
        }
        // Handle short format owner/repo or owner/repo/subdir
        else {
            String[] parts = specifier.split("/");
            if (parts.length < 2) {
                throw new Exception("Invalid repository format. Use: owner/repo or owner/repo/skill-name");
            }

            info.repoUrl = GITHUB_BASE_URL + parts[0] + "/" + parts[1];
            info.skillName = parts.length >= 3 ? parts[2] : parts[1];
            info.subdir = parts.length >= 3 ? parts[2] : null;
        }

        return info;
    }

    /**
     * Check if git command is available
     *
     * @return true if git is available
     */
    private boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                .redirectErrorStream(true)
                .start();

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clone Git repository
     *
     * @param repoUrl Repository URL
     * @param targetDir Target directory
     * @throws Exception Clone failure
     */
    private void cloneRepository(String repoUrl, String targetDir) throws Exception {
        String httpsError = executeGitClone(repoUrl, targetDir);

        if (httpsError == null) {
            return;
        }

        // HTTPS failed due to network issue, try SSH
        boolean isNetworkError = httpsError.contains("Failed to connect")
                || httpsError.contains("Could not resolve host")
                || httpsError.contains("Connection refused")
                || httpsError.contains("Connection timed out")
                || httpsError.contains("Couldn't connect to server");

        String sshUrl = convertToSshUrl(repoUrl);
        if (isNetworkError && sshUrl != null) {
            logger.info("HTTPS clone failed, retrying with SSH: https_url={}, ssh_url={}", repoUrl, sshUrl);

            // Clean up directory left by failed HTTPS attempt
            deleteDirectory(new File(targetDir));
            Files.createDirectories(Paths.get(targetDir));

            String sshError = executeGitClone(sshUrl, targetDir);
            if (sshError == null) {
                return;
            }

            // SSH also failed, throw exception with both error messages
            throw new Exception("Failed to clone repository.\n"
                    + "HTTPS (" + repoUrl + "): " + httpsError.trim() + "\n"
                    + "SSH (" + sshUrl + "): " + sshError.trim() + "\n\n"
                    + "Please check network connection or configure git proxy:\n"
                    + "  git config --global http.proxy http://proxy-host:port\n"
                    + "  git config --global https.proxy http://proxy-host:port");
        }

        // Non-network issue or cannot convert to SSH URL
        if (httpsError.contains("not found") || httpsError.contains("404")) {
            throw new Exception("Repository does not exist or no access permission: " + repoUrl);
        } else if (httpsError.contains("Authentication failed")) {
            throw new Exception("Authentication failed. Please check repository access permissions.");
        } else {
            throw new Exception("Failed to clone repository: " + httpsError);
        }
    }

    /**
     * Execute git clone command
     *
     * @param url Repository URL (HTTPS or SSH)
     * @param targetDir Target directory
     * @return null if success, error message if failure
     */
    private String executeGitClone(String url, String targetDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", url, targetDir
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 ? null : output.toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Convert HTTPS GitHub URL to SSH URL
     *
     * @param httpsUrl HTTPS format URL
     * @return SSH format URL, null if cannot convert
     */
    private String convertToSshUrl(String httpsUrl) {
        if (httpsUrl == null) {
            return null;
        }
        // https://github.com/owner/repo -> git@github.com:owner/repo.git
        if (httpsUrl.startsWith("https://github.com/")) {
            String path = httpsUrl.substring("https://github.com/".length());
            // Remove trailing / and .git
            path = path.replaceAll("/+$", "").replaceAll("\\.git$", "");
            return "git@github.com:" + path + ".git";
        }
        if (httpsUrl.startsWith("http://github.com/")) {
            String path = httpsUrl.substring("http://github.com/".length());
            path = path.replaceAll("/+$", "").replaceAll("\\.git$", "");
            return "git@github.com:" + path + ".git";
        }
        return null;
    }

    /**
     * Recursively copy directory
     *
     * @param source Source directory
     * @param target Target directory
     * @throws IOException Copy failure
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
            .forEach(sourcePath -> {
                try {
                    Path relativePath = source.relativize(sourcePath);
                    Path targetPath = target.resolve(relativePath);

                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        // Skip .git directory
                        if (sourcePath.toString().contains(".git")) {
                            return;
                        }
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy file: " + e.getMessage(), e);
                }
            });
    }

    /**
     * Recursively delete directory
     *
     * @param dir Directory to delete
     */
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }

    /**
     * Repository info internal class
     */
    private static class RepoInfo {
        String repoUrl;     // Full repository URL
        String skillName;   // Skill name
        String subdir;      // Subdirectory (optional)
    }
}

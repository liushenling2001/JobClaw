package io.jobclaw.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 文件工具类
 */
public class FileUtil {

    /**
     * 确保目录存在，如果不存在则创建
     */
    public static void ensureDirectoryExists(String path) throws IOException {
        Files.createDirectories(Paths.get(path));
    }

    /**
     * 递归删除目录
     */
    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 展开 ~ 为用户主目录
     */
    public static String expandHome(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("~")) {
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

    /**
     * 将路径转换为安全的文件名
     */
    public static String toSafeFileName(String name) {
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[:/\\\\*?\"<>|]", "_");
    }
}

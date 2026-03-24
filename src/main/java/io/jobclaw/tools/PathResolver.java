package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 统一路径解析器 - 所有工具使用它来解析文件路径
 */
@Component
public class PathResolver {

    private final Config config;

    public PathResolver(Config config) {
        this.config = config;
    }

    /**
     * 解析路径 - 相对于 workspace 目录
     * 
     * @param path 输入路径（可以是相对路径或绝对路径）
     * @return 解析后的绝对路径字符串
     */
    public String resolve(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        
        Path p = Paths.get(path);
        
        // 如果是绝对路径，直接返回
        if (p.isAbsolute()) {
            return p.normalize().toString();
        }
        
        // 相对路径，基于 workspace 解析
        return Paths.get(config.getWorkspacePath())
                .resolve(path)
                .normalize()
                .toString();
    }

    /**
     * 获取工作目录
     */
    public String getWorkingDirectory() {
        return config.getWorkspacePath();
    }
}

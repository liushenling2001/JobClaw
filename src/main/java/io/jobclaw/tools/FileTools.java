package io.jobclaw.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件操作工具集合 - 基于 Spring AI @Tool 注解
 */
@Component
public class FileTools {

    public FileTools() {
    }

    @Tool(name = "read_file", description = "Read the contents of a file")
    public String readFile(
        @ToolParam(description = "The path of the file to read") String path
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        try {
            String content = Files.readString(Paths.get(path));
            return content;
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(name = "write_file", description = "Write content to a file (create or overwrite)")
    public String writeFile(
        @ToolParam(description = "The path of the file to write") String path,
        @ToolParam(description = "The content to write") String content
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }
        if (content == null) {
            return "Error: content is required";
        }

        try {
            Files.createDirectories(Paths.get(path).getParent());
            Files.writeString(Paths.get(path), content);
            return "Successfully wrote to " + path;
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(name = "list_dir", description = "List the contents of a directory")
    public String listDir(
        @ToolParam(description = "The path of the directory to list") String path
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        try {
            List<String> entries = new java.util.ArrayList<>();
            Files.list(Paths.get(path)).forEach(p -> {
                String type = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                entries.add(type + p.getFileName());
            });

            return String.join("\n", entries);
        } catch (Exception e) {
            return "Error listing directory: " + e.getMessage();
        }
    }
}

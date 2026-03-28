package io.jobclaw.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReadFileTool implements Tool {

    private final PathResolver pathResolver;

    public ReadFileTool(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The path of the file to read (relative to workspace or absolute)");
        properties.put("path", pathProp);

        params.put("properties", properties);

        String[] required = {"path"};
        params.put("required", required);

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String path = (String) args.get("path");
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        try {
            // 使用 PathResolver 解析路径
            String resolvedPath = pathResolver.resolve(path);
            String content = Files.readString(Paths.get(resolvedPath));
            return content;
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}

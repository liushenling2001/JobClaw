package io.jobclaw.tools;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListDirTool implements Tool {

    private final PathResolver pathResolver;

    public ListDirTool(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Override
    public String getName() {
        return "list_dir";
    }

    @Override
    public String getDescription() {
        return "List the contents of a directory";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The path of the directory to list (relative to workspace or absolute)");
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
            List<String> entries = new ArrayList<>();
            Files.list(Paths.get(resolvedPath)).forEach(p -> {
                String type = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                entries.add(type + p.getFileName());
            });

            return String.join("\n", entries);
        } catch (Exception e) {
            return "Error listing directory: " + e.getMessage();
        }
    }
}

package io.jobclaw.tools;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListDirTool implements Tool {

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
        pathProp.put("description", "The path of the directory to list");
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
            List<String> entries = new ArrayList<>();
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

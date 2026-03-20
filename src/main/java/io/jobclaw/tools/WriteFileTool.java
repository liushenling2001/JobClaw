package io.jobclaw.tools;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class WriteFileTool implements Tool {

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Write content to a file (create or overwrite)";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The path of the file to write");
        properties.put("path", pathProp);

        Map<String, Object> contentProp = new HashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "The content to write");
        properties.put("content", contentProp);

        params.put("properties", properties);

        String[] required = {"path", "content"};
        params.put("required", required);

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String path = (String) args.get("path");
        String content = (String) args.get("content");

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
}

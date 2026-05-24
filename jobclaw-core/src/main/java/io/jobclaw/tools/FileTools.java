package io.jobclaw.tools;

import io.jobclaw.config.Config;
import io.jobclaw.agent.AgentExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 文件操作工具集合 - 基于 Spring AI @Tool 注解
 */
@Component
public class FileTools {
    private static final Logger logger = LoggerFactory.getLogger(FileTools.class);
    private static final String DOCUMENT_PARSER_REQUIRED =
            "document-parser skill is required. Install or enable the office-parser skill, then use its document parsing command for this file type.";

    private final Config config;

    public FileTools(Config config) {
        this.config = config;
    }

    @Tool(name = "read_file", description = "Read the contents of a file. Use the exact path returned by list_dir; copy it verbatim and do not insert, remove, or reformat spaces in file names.")
    public String readFile(
        @ToolParam(description = "Exact file path to read. Preserve every character exactly as shown by list_dir, including Chinese characters and spaces.") String path
    ) {
        try {
            Path resolvedPath = resolveExistingPath(path);
            if (!Files.exists(resolvedPath)) {
                return "Error reading file: file not found: " + path;
            }
            String content = Files.readString(resolvedPath);
            return content;
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(name = "write_file", description = "Write content to a file (create or overwrite). Preserve the path exactly; do not insert or remove spaces in file names.")
    public String writeFile(
        @ToolParam(description = "Exact path of the file to write") String path,
        @ToolParam(description = "The content to write") String content
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }
        if (content == null) {
            return "Error: content is required";
        }

        try {
            Path resolvedPath = resolvePath(path);
            if (resolvedPath.getParent() != null) {
                Files.createDirectories(resolvedPath.getParent());
            }
            Files.writeString(resolvedPath, content);
            String absolutePath = resolvedPath.toAbsolutePath().normalize().toString();
            logger.info("write_file wrote path={} chars={}", absolutePath, content.length());
            return "Successfully wrote to " + absolutePath;
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(name = "list_dir", description = "List the contents of a directory. The output includes exact absolute paths; use those paths verbatim for later file tools.")
    public String listDir(
        @ToolParam(description = "The exact path of the directory to list") String path
    ) {
        try {
            Path resolvedPath = resolvePath(path);
            List<String> entries = new java.util.ArrayList<>();
            try (Stream<Path> stream = Files.list(resolvedPath)) {
                stream.forEach(p -> {
                    String type = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                    entries.add(type + p.getFileName() + " | path=\"" + p.toAbsolutePath().normalize() + "\"");
                });
            }

            return String.join("\n", entries);
        } catch (Exception e) {
            return "Error listing directory: " + e.getMessage();
        }
    }

    @Tool(name = "read_word", description = "Read the contents of a Word document (.doc or .docx). Use the exact path returned by list_dir; do not insert, remove, or reformat spaces in file names.")
    public String readWord(
        @ToolParam(description = "Exact path of the Word document (.doc or .docx)") String path,
        @ToolParam(description = "Number of pages to read from the beginning (optional)") String frontPages,
        @ToolParam(description = "Number of random middle pages to read (optional)") String randomPages,
        @ToolParam(description = "Number of pages to read from the end (optional)") String tailPages
    ) {
        try {
            Path resolvedPath = requireReadablePath(path, ".doc", ".docx");
            return documentParserRequired("Word document", resolvedPath);
        } catch (Exception e) {
            return "Error reading Word document: " + e.getMessage();
        }
    }

    @Tool(name = "read_excel", description = "Read the contents of an Excel workbook (.xls or .xlsx). Use the exact path returned by list_dir; do not insert, remove, or reformat spaces in file names.")
    public String readExcel(
        @ToolParam(description = "Exact path of the Excel workbook (.xls or .xlsx)") String path,
        @ToolParam(description = "Sheet name or index (0-based, optional, default: 0)") String sheet
    ) {
        try {
            Path resolvedPath = requireReadablePath(path, ".xls", ".xlsx", ".csv");
            return documentParserRequired("Excel workbook", resolvedPath);
        } catch (Exception e) {
            return "Error reading Excel workbook: " + e.getMessage();
        }
    }

    @Tool(name = "read_pdf", description = "Read the contents of a PDF document (.pdf). Use the exact path returned by list_dir; do not insert, remove, or reformat spaces in file names.")
    public String readPdf(
        @ToolParam(description = "Exact path of the PDF document (.pdf)") String path,
        @ToolParam(description = "Number of pages to read from the beginning (optional)") String frontPages,
        @ToolParam(description = "Number of random middle pages to read (optional)") String randomPages,
        @ToolParam(description = "Number of pages to read from the end (optional)") String tailPages
    ) {
        try {
            Path resolvedPath = requireReadablePath(path, ".pdf");
            return documentParserRequired("PDF document", resolvedPath);
        } catch (Exception e) {
            return "Error reading PDF document: " + e.getMessage();
        }
    }

    @Tool(name = "edit_file", description = "Edit a file by replacing exact text (old_text must match exactly). Preserve the path exactly; do not insert or remove spaces in file names.")
    public String editFile(
        @ToolParam(description = "Exact path of the file to edit") String path,
        @ToolParam(description = "The exact text to find and replace (must match exactly)") String oldText,
        @ToolParam(description = "The new text to replace the old text with") String newText
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }
        if (oldText == null) {
            return "Error: old_text is required";
        }
        if (newText == null) {
            return "Error: new_text is required";
        }

        try {
            java.nio.file.Path resolvedPath = resolvePath(path);
            
            if (!Files.exists(resolvedPath)) {
                return "Error: file not found: " + path;
            }

            String content = Files.readString(resolvedPath);

            if (!content.contains(oldText)) {
                return "Error: old_text not found in file. Make sure it matches exactly.";
            }

            int count = countOccurrences(content, oldText);
            if (count > 1) {
                return "Error: old_text appears " + count + " times. Provide more context.";
            }

            String newContent = content.replace(oldText, newText);
            Files.writeString(resolvedPath, newContent);
            String absolutePath = resolvedPath.toAbsolutePath().normalize().toString();
            logger.info("edit_file edited path={} replacements={} chars={}", absolutePath, count, newContent.length());
            return "Successfully edited " + absolutePath;
        } catch (Exception e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    @Tool(name = "append_file", description = "Append content to end of file (creates if not exists). Preserve the path exactly; do not insert or remove spaces in file names.")
    public String appendFile(
        @ToolParam(description = "Exact path of the file to append to") String path,
        @ToolParam(description = "The content to append") String content
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }
        if (content == null) {
            return "Error: content is required";
        }

        try {
            java.nio.file.Path resolvedPath = resolvePath(path);
            java.nio.file.Path parentDir = resolvedPath.getParent();
            
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.writeString(resolvedPath, content, 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.APPEND);
            String absolutePath = resolvedPath.toAbsolutePath().normalize().toString();
            logger.info("append_file appended path={} chars={}", absolutePath, content.length());
            return "Successfully appended to " + absolutePath;
        } catch (Exception e) {
            return "Error appending to file: " + e.getMessage();
        }
    }

    private String documentParserRequired(String type, Path path) {
        return "Error: " + DOCUMENT_PARSER_REQUIRED + "\n"
                + "File: " + path.toAbsolutePath().normalize() + "\n"
                + "Suggested skill: office-parser";
    }

    /**
     * Count occurrences of a substring in text
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    private Path resolvePath(String path) {
        Path workspace = Paths.get(firstNonBlank(
                AgentExecutionContext.getCurrentProjectRoot(),
                config.getWorkspacePath()
        ));
        if (path == null || path.isBlank()) {
            return workspace.normalize();
        }
        Path input = Paths.get(cleanPathArgument(path));
        if (input.isAbsolute()) {
            return input.normalize();
        }
        return workspace.resolve(input).normalize();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Path requireReadablePath(String path, String... allowedExtensions) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        Path resolvedPath = resolveExistingPath(path);
        String lowerPath = resolvedPath.toString().toLowerCase();
        boolean matches = false;
        for (String extension : allowedExtensions) {
            if (lowerPath.endsWith(extension)) {
                matches = true;
                break;
            }
        }
        if (!matches) {
            throw new IllegalArgumentException(
                    "file must be one of: " + String.join(", ", allowedExtensions) + ", got: " + path
            );
        }
        if (!Files.exists(resolvedPath)) {
            throw new IllegalArgumentException("file not found: " + path);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IllegalArgumentException("path is not a file: " + path);
        }
        return resolvedPath;
    }

    private Path resolveExistingPath(String path) {
        String cleanedPath = cleanPathArgument(path);
        try {
            Path resolvedPath = resolvePath(cleanedPath);
            if (Files.exists(resolvedPath)) {
                return resolvedPath;
            }
            return recoverMutatedExistingPath(resolvedPath).orElse(resolvedPath);
        } catch (InvalidPathException e) {
            return recoverInvalidExistingPath(cleanedPath).orElseThrow(() -> e);
        }
    }

    private Optional<Path> recoverInvalidExistingPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        int separatorIndex = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        String parentText = separatorIndex >= 0 ? path.substring(0, separatorIndex) : "";
        String requestedFileName = separatorIndex >= 0 ? path.substring(separatorIndex + 1) : path;

        try {
            Path parent = parentText.isBlank() ? Paths.get(config.getWorkspacePath()) : resolvePath(parentText);
            return recoverMutatedFileName(parent, requestedFileName);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> recoverMutatedExistingPath(Path requestedPath) {
        Path parent = requestedPath.getParent();
        Path requestedFileName = requestedPath.getFileName();
        if (parent == null || requestedFileName == null || !Files.isDirectory(parent)) {
            return Optional.empty();
        }

        return recoverMutatedFileName(parent, requestedFileName.toString());
    }

    private Optional<Path> recoverMutatedFileName(Path parent, String requestedFileName) {
        if (parent == null || requestedFileName == null || !Files.isDirectory(parent)) {
            return Optional.empty();
        }

        Set<String> requestedKeys = mutationKeys(requestedFileName);
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parent)) {
            stream.filter(path -> hasCommonMutationKey(requestedKeys, mutationKeys(path.getFileName().toString())))
                    .forEach(matches::add);
        } catch (Exception ignored) {
            return Optional.empty();
        }

        if (matches.size() == 1) {
            return Optional.of(matches.get(0).toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private boolean hasCommonMutationKey(Set<String> left, Set<String> right) {
        for (String key : left) {
            if (right.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> mutationKeys(String value) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String stripped = stripOuterPathQuotes(value == null ? "" : value.trim());
        String quoteNormalized = normalizeQuoteCharacters(stripped);
        keys.add(stripped);
        keys.add(removeWhitespace(stripped));
        keys.add(quoteNormalized);
        keys.add(removeWhitespace(quoteNormalized));
        return keys;
    }

    private String cleanPathArgument(String path) {
        if (path == null) {
            return "";
        }

        String cleaned = path.trim();
        int pathMarker = pathMarkerIndex(cleaned);
        if (pathMarker >= 0) {
            cleaned = cleaned.substring(pathMarker + "path=".length()).trim();
        }
        return unescapeQuotedPathCharacters(stripOuterPathQuotes(cleaned));
    }

    private int pathMarkerIndex(String value) {
        if (value.length() >= "path=".length()
                && value.regionMatches(true, 0, "path=", 0, "path=".length())) {
            return 0;
        }

        int pathMarker = value.toLowerCase(Locale.ROOT).lastIndexOf("path=");
        if (pathMarker >= 0 && value.substring(0, pathMarker).contains("|")) {
            return pathMarker;
        }
        return -1;
    }

    private String stripOuterPathQuotes(String value) {
        String cleaned = value == null ? "" : value.trim();
        boolean changed;
        do {
            changed = false;
            if (cleaned.length() >= 2
                    && isPathQuote(cleaned.charAt(0))
                    && isPathQuote(cleaned.charAt(cleaned.length() - 1))) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
                changed = true;
            }
        } while (changed);
        return cleaned;
    }

    private boolean isPathQuote(char ch) {
        return ch == '"' || ch == '\'' || ch == '`'
                || ch == '“' || ch == '”'
                || ch == '‘' || ch == '’'
                || ch == '＂' || ch == '＇';
    }

    private String normalizeQuoteCharacters(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (isPathQuote(ch)) {
                sb.append('"');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String unescapeQuotedPathCharacters(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length() && isPathQuote(value.charAt(i + 1))) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private String removeWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

}

package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * 文件操作工具集合 - 基于 Spring AI @Tool 注解
 */
@Component
public class FileTools {
    private static final int ESTIMATED_WORD_PAGE_CHAR_COUNT = 1800;

    private final Config config;
    private final Tika tika;

    public FileTools(Config config) {
        this.config = config;
        this.tika = new Tika();
    }

    @Tool(name = "read_file", description = "Read the contents of a file. Use the exact path returned by list_dir; copy it verbatim and do not insert, remove, or reformat spaces in file names.")
    public String readFile(
        @ToolParam(description = "Exact file path to read. Preserve every character exactly as shown by list_dir, including Chinese characters and spaces.") String path
    ) {
        try {
            Path resolvedPath = resolveExistingPath(path);
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
            return "Successfully wrote to " + path;
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
            PageSelection selection = parsePageSelection(frontPages, randomPages, tailPages);
            if (!selection.isActive()) {
                return extractDocumentText(resolvedPath, null);
            }
            return extractWordSample(resolvedPath, selection);
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
            Path resolvedPath = requireReadablePath(path, ".xls", ".xlsx");
            String extraction = extractDocumentText(
                    resolvedPath,
                    "Note: Apache Tika currently returns workbook text for all sheets; the sheet parameter is ignored."
            );
            if (sheet == null || sheet.isBlank()) {
                return extraction;
            }
            return extraction;
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
            PageSelection selection = parsePageSelection(frontPages, randomPages, tailPages);
            if (!selection.isActive()) {
                return extractDocumentText(resolvedPath, null);
            }
            return extractPdfSample(resolvedPath, selection);
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
            return "Successfully edited " + path;
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
            return "Successfully appended to " + path;
        } catch (Exception e) {
            return "Error appending to file: " + e.getMessage();
        }
    }

    private String extractDocumentText(Path path, String note) throws Exception {
        String content = tika.parseToString(path);
        if (content == null || content.isBlank()) {
            return "Error: no text content could be extracted from " + path.getFileName();
        }
        if (note == null || note.isBlank()) {
            return content.trim();
        }
        return note + "\n\n" + content.trim();
    }

    private String extractPdfSample(Path path, PageSelection selection) throws Exception {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            int totalPages = document.getNumberOfPages();
            if (totalPages <= 0) {
                return "Error: PDF contains no readable pages";
            }

            List<Integer> pages = choosePages(totalPages, selection);
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder content = new StringBuilder();
            content.append("PDF page sample from ").append(path.getFileName()).append('\n');
            content.append("Total pages: ").append(totalPages).append('\n');
            content.append("Selected pages: ").append(formatPageList(pages)).append("\n\n");

            for (int page : pages) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();
                content.append("=== Page ").append(page).append(" ===\n");
                content.append(pageText.isBlank() ? "[no readable text]" : pageText).append("\n\n");
            }
            return content.toString().trim();
        }
    }

    private String extractWordSample(Path path, PageSelection selection) throws Exception {
        List<String> pages = extractWordPages(path);
        if (pages.isEmpty()) {
            return "Error: Word document contains no readable text";
        }

        List<Integer> selectedPages = choosePages(pages.size(), selection);
        StringBuilder content = new StringBuilder();
        content.append("Word page sample from ").append(path.getFileName()).append('\n');
        content.append("Total estimated pages: ").append(pages.size()).append('\n');
        content.append("Selected estimated pages: ").append(formatPageList(selectedPages)).append('\n');
        content.append("Note: Word page boundaries are estimated and may not match Office pagination exactly.\n\n");

        for (int page : selectedPages) {
            String pageText = pages.get(page - 1).trim();
            content.append("=== Estimated Page ").append(page).append(" ===\n");
            content.append(pageText.isBlank() ? "[no readable text]" : pageText).append("\n\n");
        }
        return content.toString().trim();
    }

    private List<String> extractWordPages(Path path) throws Exception {
        String lowerPath = path.toString().toLowerCase();
        if (lowerPath.endsWith(".docx")) {
            return extractDocxPages(path);
        }
        String content = tika.parseToString(path);
        return splitEstimatedPages(content);
    }

    private List<String> extractDocxPages(Path path) throws Exception {
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        try (InputStream inputStream = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                appendParagraph(current, paragraph);
                if (containsPageBreak(paragraph)) {
                    flushEstimatedWordPage(pages, current, true);
                    continue;
                }
                if (current.length() >= ESTIMATED_WORD_PAGE_CHAR_COUNT) {
                    flushEstimatedWordPage(pages, current, false);
                }
            }
        }

        flushEstimatedWordPage(pages, current, true);
        return pages;
    }

    private void appendParagraph(StringBuilder current, XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text != null && !text.isBlank()) {
            current.append(text.trim()).append('\n');
        }
    }

    private boolean containsPageBreak(XWPFParagraph paragraph) {
        for (XWPFRun run : paragraph.getRuns()) {
            if (run != null && run.getCTR() != null && run.getCTR().sizeOfBrArray() > 0) {
                return true;
            }
        }
        return false;
    }

    private void flushEstimatedWordPage(List<String> pages, StringBuilder current, boolean allowEmpty) {
        String text = current.toString().trim();
        if (!text.isEmpty() || allowEmpty) {
            if (!text.isEmpty()) {
                pages.add(text);
            }
        }
        current.setLength(0);
    }

    private List<String> splitEstimatedPages(String content) {
        List<String> pages = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return pages;
        }

        String normalized = content.trim();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + ESTIMATED_WORD_PAGE_CHAR_COUNT);
            pages.add(normalized.substring(start, end).trim());
            start = end;
        }
        return pages;
    }

    private PageSelection parsePageSelection(String frontPages, String randomPages, String tailPages) {
        return new PageSelection(
                parseNonNegative(frontPages),
                parseNonNegative(randomPages),
                parseNonNegative(tailPages)
        );
    }

    private int parseNonNegative(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 0) {
            throw new IllegalArgumentException("page selection values must be >= 0");
        }
        return parsed;
    }

    private List<Integer> choosePages(int totalPages, PageSelection selection) {
        LinkedHashSet<Integer> picked = new LinkedHashSet<>();

        for (int page = 1; page <= Math.min(selection.frontPages(), totalPages); page++) {
            picked.add(page);
        }

        int tailStart = Math.max(1, totalPages - selection.tailPages() + 1);
        for (int page = tailStart; page <= totalPages; page++) {
            picked.add(page);
        }

        List<Integer> middleCandidates = new ArrayList<>();
        for (int page = 1; page <= totalPages; page++) {
            if (!picked.contains(page)) {
                middleCandidates.add(page);
            }
        }

        int randomCount = Math.min(selection.randomPages(), middleCandidates.size());
        for (int i = 0; i < randomCount; i++) {
            int index = ThreadLocalRandom.current().nextInt(middleCandidates.size());
            picked.add(middleCandidates.remove(index));
        }

        if (picked.isEmpty()) {
            for (int page = 1; page <= totalPages; page++) {
                picked.add(page);
            }
        }

        return picked.stream().sorted().toList();
    }

    private String formatPageList(List<Integer> pages) {
        return pages.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("-");
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
        Path workspace = Paths.get(config.getWorkspacePath());
        if (path == null || path.isBlank()) {
            return workspace.normalize();
        }
        Path input = Paths.get(path);
        if (input.isAbsolute()) {
            return input.normalize();
        }
        return workspace.resolve(input).normalize();
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
        Path resolvedPath = resolvePath(path);
        if (Files.exists(resolvedPath)) {
            return resolvedPath;
        }
        return recoverWhitespaceMutatedPath(resolvedPath).orElse(resolvedPath);
    }

    private Optional<Path> recoverWhitespaceMutatedPath(Path requestedPath) {
        Path parent = requestedPath.getParent();
        Path requestedFileName = requestedPath.getFileName();
        if (parent == null || requestedFileName == null || !Files.isDirectory(parent)) {
            return Optional.empty();
        }

        String compactRequestedName = removeWhitespace(requestedFileName.toString());
        if (compactRequestedName.equals(requestedFileName.toString())) {
            return Optional.empty();
        }

        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parent)) {
            stream.filter(path -> removeWhitespace(path.getFileName().toString()).equals(compactRequestedName))
                    .forEach(matches::add);
        } catch (Exception ignored) {
            return Optional.empty();
        }

        if (matches.size() == 1) {
            return Optional.of(matches.get(0).toAbsolutePath().normalize());
        }
        return Optional.empty();
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

    private record PageSelection(int frontPages, int randomPages, int tailPages) {
        private boolean isActive() {
            return frontPages > 0 || randomPages > 0 || tailPages > 0;
        }
    }
}

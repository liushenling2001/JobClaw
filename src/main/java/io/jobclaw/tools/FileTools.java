package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件操作工具集合 - 基于 Spring AI @Tool 注解
 */
@Component
public class FileTools {

    private final Config config;

    public FileTools(Config config) {
        this.config = config;
    }

    @Tool(name = "read_file", description = "Read the contents of a file")
    public String readFile(
        @ToolParam(description = "The path of the file to read") String path
    ) {
        try {
            Path resolvedPath = resolvePath(path);
            String content = Files.readString(resolvedPath);
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

    @Tool(name = "list_dir", description = "List the contents of a directory")
    public String listDir(
        @ToolParam(description = "The path of the directory to list") String path
    ) {
        try {
            Path resolvedPath = resolvePath(path);
            List<String> entries = new java.util.ArrayList<>();
            Files.list(resolvedPath).forEach(p -> {
                String type = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                entries.add(type + p.getFileName());
            });

            return String.join("\n", entries);
        } catch (Exception e) {
            return "Error listing directory: " + e.getMessage();
        }
    }

    @Tool(name = "read_word", description = "Read the contents of a Word document (.doc or .docx)")
    public String readWord(
        @ToolParam(description = "The path of the Word document (.doc or .docx)") String path
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        String resolvedPath = resolvePath(path).toString();
        String lowerPath = resolvedPath.toLowerCase();
        try {
            if (lowerPath.endsWith(".docx")) {
                return readDocx(resolvedPath);
            } else if (lowerPath.endsWith(".doc")) {
                return readDoc(resolvedPath);
            } else {
                return "Error: file must be a .doc or .docx file, got: " + path;
            }
        } catch (Exception e) {
            return "Error reading Word document: " + e.getMessage();
        }
    }

    @Tool(name = "read_excel", description = "Read the contents of an Excel workbook (.xls or .xlsx)")
    public String readExcel(
        @ToolParam(description = "The path of the Excel workbook (.xls or .xlsx)") String path,
        @ToolParam(description = "Sheet name or index (0-based, optional, default: 0)") String sheet
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        String resolvedPath = resolvePath(path).toString();
        String lowerPath = resolvedPath.toLowerCase();
        try {
            if (lowerPath.endsWith(".xlsx")) {
                return readXlsx(resolvedPath, sheet);
            } else if (lowerPath.endsWith(".xls")) {
                return readXls(resolvedPath, sheet);
            } else {
                return "Error: file must be a .xls or .xlsx file, got: " + path;
            }
        } catch (Exception e) {
            return "Error reading Excel workbook: " + e.getMessage();
        }
    }

    @Tool(name = "edit_file", description = "Edit a file by replacing exact text (old_text must match exactly)")
    public String editFile(
        @ToolParam(description = "The path of the file to edit") String path,
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

    @Tool(name = "append_file", description = "Append content to end of file (creates if not exists)")
    public String appendFile(
        @ToolParam(description = "The path of the file to append to") String path,
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

    /**
     * Read .docx file (Office 2007+)
     */
    private String readDocx(String path) throws Exception {
        StringBuilder content = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(path);
             XWPFDocument document = new XWPFDocument(fis)) {
            
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isEmpty()) {
                    content.append(text).append("\n");
                }
            }

            document.getTables().forEach(table -> {
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell -> {
                        content.append(cell.getText()).append("\t");
                    });
                    content.append("\n");
                });
                content.append("\n");
            });
        }
        return content.toString().trim();
    }

    /**
     * Read .doc file (Office 97-2003)
     */
    private String readDoc(String path) throws Exception {
        StringBuilder content = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(path);
             HWPFDocument document = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(document)) {
            
            String[] paragraphs = extractor.getParagraphText();
            for (String paragraph : paragraphs) {
                if (paragraph != null && !paragraph.trim().isEmpty()) {
                    content.append(paragraph.trim()).append("\n");
                }
            }
        }
        return content.toString().trim();
    }

    /**
     * Read .xlsx file (Office 2007+)
     */
    private String readXlsx(String path, String sheetParam) throws Exception {
        StringBuilder content = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(path);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            
            XSSFSheet sheetData;
            int sheetIndex = 0;
            
            if (sheetParam != null && !sheetParam.isEmpty()) {
                try {
                    sheetIndex = Integer.parseInt(sheetParam);
                    sheetData = workbook.getSheetAt(sheetIndex);
                } catch (NumberFormatException e) {
                    sheetData = workbook.getSheet(sheetParam);
                    if (sheetData == null) {
                        return "Error: sheet '" + sheetParam + "' not found";
                    }
                }
            } else {
                sheetData = workbook.getSheetAt(0);
            }

            if (sheetData == null) {
                return "Error: sheet at index " + sheetIndex + " not found";
            }

            content.append("Sheet: ").append(sheetData.getSheetName()).append("\n\n");

            sheetData.forEach(row -> {
                row.forEach(cell -> {
                    content.append(getCellValueAsString(cell)).append("\t");
                });
                content.append("\n");
            });
        }
        return content.toString().trim();
    }

    /**
     * Read .xls file (Office 97-2003)
     */
    private String readXls(String path, String sheetParam) throws Exception {
        StringBuilder content = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(path);
             HSSFWorkbook workbook = new HSSFWorkbook(fis)) {
            
            Sheet sheetData;
            int sheetIndex = 0;
            
            if (sheetParam != null && !sheetParam.isEmpty()) {
                try {
                    sheetIndex = Integer.parseInt(sheetParam);
                    sheetData = workbook.getSheetAt(sheetIndex);
                } catch (NumberFormatException e) {
                    sheetData = workbook.getSheet(sheetParam);
                    if (sheetData == null) {
                        return "Error: sheet '" + sheetParam + "' not found";
                    }
                }
            } else {
                sheetData = workbook.getSheetAt(0);
            }

            if (sheetData == null) {
                return "Error: sheet at index " + sheetIndex + " not found";
            }

            content.append("Sheet: ").append(sheetData.getSheetName()).append("\n\n");

            for (Row row : sheetData) {
                row.forEach(cell -> {
                    content.append(getCellValueAsString(cell)).append("\t");
                });
                content.append("\n");
            }
        }
        return content.toString().trim();
    }

    /**
     * Convert Excel cell value to string
     */
    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                } else {
                    double num = cell.getNumericCellValue();
                    if (num == (long) num) {
                        yield String.valueOf((long) num);
                    } else {
                        yield String.valueOf(num);
                    }
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
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
}

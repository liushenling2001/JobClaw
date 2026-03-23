package io.jobclaw.tools;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
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

    @Tool(name = "read_word", description = "Read the contents of a Word document (.docx)")
    public String readWord(
        @ToolParam(description = "The path of the Word document (.docx)") String path
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        try {
            if (!path.toLowerCase().endsWith(".docx")) {
                return "Error: file must be a .docx file";
            }

            StringBuilder content = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(path);
                 XWPFDocument document = new XWPFDocument(fis)) {
                
                // Extract paragraphs
                List<XWPFParagraph> paragraphs = document.getParagraphs();
                for (XWPFParagraph paragraph : paragraphs) {
                    String text = paragraph.getText();
                    if (text != null && !text.isEmpty()) {
                        content.append(text).append("\n");
                    }
                }

                // Extract tables
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
        } catch (Exception e) {
            return "Error reading Word document: " + e.getMessage();
        }
    }

    @Tool(name = "read_excel", description = "Read the contents of an Excel workbook (.xlsx)")
    public String readExcel(
        @ToolParam(description = "The path of the Excel workbook (.xlsx)") String path,
        @ToolParam(description = "Sheet name or index (0-based, optional, default: 0)") String sheet
    ) {
        if (path == null || path.isEmpty()) {
            return "Error: path is required";
        }

        try {
            if (!path.toLowerCase().endsWith(".xlsx")) {
                return "Error: file must be a .xlsx file";
            }

            StringBuilder content = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(path);
                 XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
                
                XSSFSheet sheetData;
                int sheetIndex = 0;
                
                // Parse sheet parameter
                if (sheet != null && !sheet.isEmpty()) {
                    try {
                        sheetIndex = Integer.parseInt(sheet);
                        sheetData = workbook.getSheetAt(sheetIndex);
                    } catch (NumberFormatException e) {
                        // Try to get by name
                        sheetData = workbook.getSheet(sheet);
                        if (sheetData == null) {
                            return "Error: sheet '" + sheet + "' not found";
                        }
                    }
                } else {
                    sheetData = workbook.getSheetAt(0);
                }

                if (sheetData == null) {
                    return "Error: sheet at index " + sheetIndex + " not found";
                }

                content.append("Sheet: ").append(sheetData.getSheetName()).append("\n\n");

                // Iterate through rows
                sheetData.forEach(row -> {
                    row.forEach(cell -> {
                        String cellValue = getCellValueAsString(cell);
                        content.append(cellValue).append("\t");
                    });
                    content.append("\n");
                });
            }

            return content.toString().trim();
        } catch (Exception e) {
            return "Error reading Excel workbook: " + e.getMessage();
        }
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
}

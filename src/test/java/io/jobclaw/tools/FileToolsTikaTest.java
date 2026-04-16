package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsTikaTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadDocxWithTika() throws Exception {
        Path docx = tempDir.resolve("sample.docx");
        createDocx(docx, "Word sample text", "Second paragraph");

        FileTools tools = createTools();
        String content = tools.readWord(docx.toString(), null, null, null);

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("Word sample text"), content);
        assertTrue(content.contains("Second paragraph"), content);
    }

    @Test
    void shouldReadXlsxWithTika() throws Exception {
        Path xlsx = tempDir.resolve("sample.xlsx");
        createXlsx(xlsx);

        FileTools tools = createTools();
        String content = tools.readExcel(xlsx.toString(), null);

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("Revenue"), content);
        assertTrue(content.contains("North"), content);
        assertTrue(content.contains("128"), content);
    }

    @Test
    void shouldReadPdfWithTika() throws Exception {
        Path pdf = tempDir.resolve("sample.pdf");
        createPdf(pdf, "PDF smoke test");

        FileTools tools = createTools();
        String content = tools.readPdf(pdf.toString(), null, null, null);

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("PDF smoke test"), content);
    }

    @Test
    void shouldReadPdfPageSamples() throws Exception {
        Path pdf = tempDir.resolve("sample-pages.pdf");
        createPdf(pdf, "Page 1 text", "Page 2 text", "Page 3 text", "Page 4 text", "Page 5 text");

        FileTools tools = createTools();
        String content = tools.readPdf(pdf.toString(), "1", "1", "1");

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("Selected pages:"), content);
        assertTrue(content.contains("=== Page 1 ==="), content);
        assertTrue(content.contains("=== Page 5 ==="), content);
    }

    @Test
    void shouldReadWordEstimatedPageSamples() throws Exception {
        Path docx = tempDir.resolve("sample-pages.docx");
        createDocx(
                docx,
                "Page alpha ".repeat(220),
                "Page beta ".repeat(220),
                "Page gamma ".repeat(220)
        );

        FileTools tools = createTools();
        String content = tools.readWord(docx.toString(), "1", "1", "1");

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("Total estimated pages:"), content);
        assertTrue(content.contains("Selected estimated pages:"), content);
        assertTrue(content.contains("=== Estimated Page"), content);
    }

    private FileTools createTools() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        return new FileTools(config);
    }

    private void createDocx(Path path, String... paragraphs) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             OutputStream outputStream = Files.newOutputStream(path)) {
            for (String text : paragraphs) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText(text);
            }
            document.write(outputStream);
        }
    }

    private void createXlsx(Path path) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(path)) {
            var sheet = workbook.createSheet("Metrics");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Region");
            header.createCell(1).setCellValue("Revenue");

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("North");
            row.createCell(1).setCellValue(128);

            workbook.write(outputStream);
        }
    }

    private void createPdf(Path path, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                    contentStream.newLineAtOffset(100, 700);
                    contentStream.showText(text);
                    contentStream.endText();
                }
            }
            document.save(path.toFile());
        }
    }
}

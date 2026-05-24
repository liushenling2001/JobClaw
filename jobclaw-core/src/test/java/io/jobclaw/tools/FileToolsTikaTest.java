package io.jobclaw.tools;

import io.jobclaw.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsDocumentParserSkillTest {

    @TempDir
    Path tempDir;

    @Test
    void readWordShouldRequireOfficeParserSkill() throws Exception {
        Path docx = tempDir.resolve("sample.docx");
        Files.writeString(docx, "Word sample text");

        FileTools tools = createTools();
        String content = tools.readWord(docx.toString(), null, null, null);

        assertTrue(content.contains("document-parser skill is required"), content);
        assertTrue(content.contains("office-parser"), content);
    }

    @Test
    void readExcelShouldRequireOfficeParserSkill() throws Exception {
        Path xlsx = tempDir.resolve("sample.xlsx");
        Files.writeString(xlsx, "Revenue");

        FileTools tools = createTools();
        String content = tools.readExcel(xlsx.toString(), null);

        assertTrue(content.contains("document-parser skill is required"), content);
        assertTrue(content.contains("office-parser"), content);
    }

    @Test
    void readPdfShouldRequireOfficeParserSkill() throws Exception {
        Path pdf = tempDir.resolve("sample.pdf");
        Files.writeString(pdf, "PDF smoke test");

        FileTools tools = createTools();
        String content = tools.readPdf(pdf.toString(), null, null, null);

        assertTrue(content.contains("document-parser skill is required"), content);
        assertTrue(content.contains("office-parser"), content);
    }

    @Test
    void readPdfPageArgumentsShouldStillRequireOfficeParserSkill() throws Exception {
        Path pdf = tempDir.resolve("sample-pages.pdf");
        Files.writeString(pdf, "Page 1 text");

        FileTools tools = createTools();
        String content = tools.readPdf(pdf.toString(), "1", "1", "1");

        assertTrue(content.contains("document-parser skill is required"), content);
        assertTrue(content.contains("office-parser"), content);
    }

    @Test
    void readWordPageArgumentsShouldStillRequireOfficeParserSkill() throws Exception {
        Path docx = tempDir.resolve("sample-pages.docx");
        Files.writeString(docx, "Page alpha");

        FileTools tools = createTools();
        String content = tools.readWord(docx.toString(), "1", "1", "1");

        assertTrue(content.contains("document-parser skill is required"), content);
        assertTrue(content.contains("office-parser"), content);
    }

    @Test
    void listDirShouldReturnExactAbsolutePathsForReuse() throws Exception {
        Path file = tempDir.resolve("桌面文件.txt");
        Files.writeString(file, "exact path");

        FileTools tools = createTools();
        String listing = tools.listDir(tempDir.toString());

        assertTrue(listing.contains("[FILE] 桌面文件.txt"), listing);
        assertTrue(listing.contains("path=\"" + file.toAbsolutePath().normalize() + "\""), listing);
    }

    @Test
    void readFileShouldRecoverWhenModelInsertsSpacesIntoFileName() throws Exception {
        Path file = tempDir.resolve("桌面文件.txt");
        Files.writeString(file, "recovered content");

        FileTools tools = createTools();
        String mutatedPath = tempDir.resolve("桌 面 文 件.txt").toString();
        String content = tools.readFile(mutatedPath);

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("recovered content"), content);
    }

    @Test
    void readFileShouldStripOuterAsciiAndSmartQuotes() throws Exception {
        Path file = tempDir.resolve("quoted-path.txt");
        Files.writeString(file, "quoted path content");

        FileTools tools = createTools();
        String asciiQuoted = tools.readFile("\"" + file + "\"");
        String smartQuoted = tools.readFile("“" + file + "”");
        String listedPathFragment = tools.readFile("[FILE] quoted-path.txt | path=\"" + file + "\"");

        assertFalse(asciiQuoted.startsWith("Error"), asciiQuoted);
        assertFalse(smartQuoted.startsWith("Error"), smartQuoted);
        assertFalse(listedPathFragment.startsWith("Error"), listedPathFragment);
        assertTrue(asciiQuoted.contains("quoted path content"), asciiQuoted);
        assertTrue(smartQuoted.contains("quoted path content"), smartQuoted);
        assertTrue(listedPathFragment.contains("quoted path content"), listedPathFragment);
    }

    @Test
    void readFileShouldPreserveSmartQuotesInsideFileName() throws Exception {
        Path file = tempDir.resolve("报告“最终”.txt");
        Files.writeString(file, "smart quote filename content");

        FileTools tools = createTools();
        String content = tools.readFile("“" + file + "”");

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("smart quote filename content"), content);
    }

    @Test
    void readFileShouldRecoverWhenModelConvertsSmartQuotesToAsciiQuotes() throws Exception {
        Path file = tempDir.resolve("报告“最终”.txt");
        Files.writeString(file, "recovered smart quote filename");

        FileTools tools = createTools();
        String mutatedPath = tempDir + java.io.File.separator + "报告\"最终\".txt";
        String content = tools.readFile(mutatedPath);

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("recovered smart quote filename"), content);
    }

    @Test
    void readFileShouldRecoverWhenModelEscapesSmartQuotes() throws Exception {
        Path file = tempDir.resolve("报告“最终”.txt");
        Files.writeString(file, "recovered escaped smart quote filename");

        FileTools tools = createTools();
        String escapedPath = file.toString().replace("“", "\\“").replace("”", "\\”");
        String content = tools.readFile(escapedPath);

        assertFalse(content.startsWith("Error"), content);
        assertTrue(content.contains("recovered escaped smart quote filename"), content);
    }

    @Test
    void readFileShouldReportFileNotFoundWhenSameFolderSearchFails() {
        FileTools tools = createTools();
        String content = tools.readFile(tempDir.resolve("missing“file”.txt").toString());

        assertTrue(content.startsWith("Error reading file: file not found:"), content);
    }

    private FileTools createTools() {
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(tempDir.toString());
        return new FileTools(config);
    }

}

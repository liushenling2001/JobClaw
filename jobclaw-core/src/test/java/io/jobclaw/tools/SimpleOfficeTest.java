package io.jobclaw.tools;

import io.jobclaw.config.Config;
import java.io.File;
import java.io.FileOutputStream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

/**
 * 简单的 Excel 工具测试
 */
public class SimpleOfficeTest {
    
    public static void main(String[] args) {
        System.out.println("=== JobClaw Office Tools 简单测试 ===\n");
        
        try {
            // 创建并测试 .xlsx
            testXlsx();
            
            // 创建并测试 .xls
            testXls();
            
            System.out.println("\n✅ 所有测试通过！");
            
        } catch (Exception e) {
            System.err.println("❌ 测试失败：" + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testXlsx() throws Exception {
        System.out.println("1️⃣ 测试 .xlsx 格式");
        
        // 创建测试文件
        String filename = "test_simple.xlsx";
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("测试数据");
            
            var row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("姓名");
            row0.createCell(1).setCellValue("年龄");
            row0.createCell(2).setCellValue("城市");
            
            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("张三");
            row1.createCell(1).setCellValue(25);
            row1.createCell(2).setCellValue("北京");
            
            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("李四");
            row2.createCell(1).setCellValue(30);
            row2.createCell(2).setCellValue("上海");
            
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                wb.write(fos);
            }
        }
        System.out.println("   ✅ 创建 " + filename);
        
        // 使用 FileTools 读取
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(new File(".").getAbsolutePath());
        FileTools tools = new FileTools(config);
        String content = tools.readExcel(filename, null);
        
        System.out.println("   ✅ 读取内容:");
        System.out.println("   ┌─────────────────────────");
        for (String line : content.split("\n")) {
            System.out.println("   │ " + line);
        }
        System.out.println("   └─────────────────────────\n");
        
        // 清理
        new File(filename).delete();
    }
    
    private static void testXls() throws Exception {
        System.out.println("2️⃣ 测试 .xls 格式");
        
        // 创建测试文件
        String filename = "test_simple.xls";
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            var sheet = wb.createSheet("测试数据");
            
            var row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("产品名称");
            row0.createCell(1).setCellValue("价格");
            row0.createCell(2).setCellValue("数量");
            
            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("苹果");
            row1.createCell(1).setCellValue(5.5);
            row1.createCell(2).setCellValue(100);
            
            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("香蕉");
            row2.createCell(1).setCellValue(3.2);
            row2.createCell(2).setCellValue(200);
            
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                wb.write(fos);
            }
        }
        System.out.println("   ✅ 创建 " + filename);
        
        // 使用 FileTools 读取
        Config config = Config.defaultConfig();
        config.getAgent().setWorkspace(new File(".").getAbsolutePath());
        FileTools tools = new FileTools(config);
        String content = tools.readExcel(filename, null);
        
        System.out.println("   ✅ 读取内容:");
        System.out.println("   ┌─────────────────────────");
        for (String line : content.split("\n")) {
            System.out.println("   │ " + line);
        }
        System.out.println("   └─────────────────────────\n");
        
        // 清理
        new File(filename).delete();
    }
}

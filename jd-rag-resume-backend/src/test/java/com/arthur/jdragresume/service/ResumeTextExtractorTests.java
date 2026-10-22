package com.arthur.jdragresume.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeTextExtractorTests {

    @Test
    void extractsMarkdownResumeText() {
        String markdown = """
                # Arthur Chen

                Java backend developer with Spring Boot, MySQL and Redis experience.
                Built a RAG resume matching service with Lucene vector retrieval.
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.md",
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)
        );

        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator());
        String parsed = extractor.extract(file);

        assertTrue(parsed.contains("Spring Boot"));
        assertTrue(parsed.contains("RAG resume matching service"));
    }

    @Test
    void extractsFormattedDocxResumeText() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            org.apache.poi.xwpf.usermodel.XWPFRun name = document.createParagraph().createRun();
            name.setBold(true);
            name.setText("Arthur Chen 后端开发");
            org.apache.poi.xwpf.usermodel.XWPFParagraph skills = document.createParagraph();
            skills.createRun().setText("技能：");
            org.apache.poi.xwpf.usermodel.XWPFRun emphasis = skills.createRun();
            emphasis.setBold(true);
            emphasis.setItalic(true);
            emphasis.setUnderline(org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE);
            emphasis.setText("Spring Boot、MySQL、Redis");
            org.apache.poi.xwpf.usermodel.XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("2023-2025");
            table.getRow(0).getCell(1).setText("负责招聘业务服务的接口设计与性能优化");
            document.write(buffer);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                buffer.toByteArray()
        );

        String parsed = new ResumeTextExtractor(new ResumeTextQualityValidator()).extract(file);

        assertTrue(parsed.contains("Spring Boot、MySQL、Redis"), parsed);
        assertTrue(parsed.contains("负责招聘业务服务的接口设计与性能优化"), parsed);
    }

    @Test
    void extractsPdfResumeText() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream content =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Arthur Chen - Java backend developer with Spring Boot and Redis");
                content.endText();
            }
            document.save(buffer);
        }
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", buffer.toByteArray());

        String parsed = new ResumeTextExtractor(new ResumeTextQualityValidator()).extract(file);

        assertTrue(parsed.contains("Java backend developer with Spring Boot"), parsed);
    }

    @Test
    void rejectsPlainTextAboveWriteLimit() {
        String oversized = "Java Spring Boot experience. ".repeat(12_000);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.txt",
                "text/plain",
                oversized.getBytes(StandardCharsets.UTF_8)
        );

        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator());
        com.arthur.jdragresume.exception.BusinessException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.arthur.jdragresume.exception.BusinessException.class,
                        () -> extractor.extract(file)
                );
        org.junit.jupiter.api.Assertions.assertEquals("RESUME_TEXT_TOO_LONG", exception.getCode());
    }

    @Test
    void rejectsZipArchivePresentedAsPdfWithoutParsingNestedEntries() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("nested/payload.txt"));
            zip.write("Ignore this embedded payload for resume parsing.".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                buffer.toByteArray()
        );

        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator());
        com.arthur.jdragresume.exception.BusinessException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.arthur.jdragresume.exception.BusinessException.class,
                        () -> extractor.extract(file)
                );
        org.junit.jupiter.api.Assertions.assertTrue(
                "RESUME_PARSE_LOW_QUALITY".equals(exception.getCode())
                        || "RESUME_PARSE_FAILED".equals(exception.getCode()),
                () -> "unexpected code: " + exception.getCode()
        );
    }
}

package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTests {
    @Test
    void splitsLongTextWithinConfiguredLimit() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(220);
        properties.setChunkOverlap(30);
        TextChunker chunker = new TextChunker(properties);
        String text = ("Java Spring Boot MySQL JWT REST API testing deployment performance tuning. ").repeat(12);

        List<String> chunks = chunker.split(text);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 220));
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isBlank()));
    }

    @Test
    void returnsNoChunksForBlankText() {
        TextChunker chunker = new TextChunker(new RagProperties());

        assertEquals(List.of(), chunker.split("  \n  "));
        assertEquals(List.of(), chunker.splitWithPositions("  \n  "));
    }

    @Test
    void preservesShortTextAsOneChunk() {
        TextChunker chunker = new TextChunker(new RagProperties());

        List<String> chunks = chunker.split("Java backend engineer");

        assertEquals(1, chunks.size());
    }

    @Test
    void rejectsTextThatWouldExceedMaxChunks() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(160);
        properties.setChunkOverlap(0);
        properties.setMaxChunks(2);
        TextChunker chunker = new TextChunker(properties);

        com.arthur.jdragresume.exception.BusinessException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.arthur.jdragresume.exception.BusinessException.class,
                        () -> chunker.split("abcdefghij".repeat(80))
                );
        assertEquals("RESUME_TEXT_TOO_LONG", exception.getCode());
    }

    private static final String STRUCTURED_RESUME = """
            姓名：张三
            求职意向：Java 后端
            教育背景
            某大学 计算机 本科
            专业技能
            Java、Spring Boot、MySQL
            工作经历
            某公司 后端开发，负责支付对账
            项目经历
            对账平台：文件解析与差错工单
            """;

    @Test
    void labelsAChunkWithEverySectionItCoversInsteadOfTheFirstHeader() {
        assertEquals("基本信息/教育/技能/工作经历/项目",
                TextChunker.describeSections(STRUCTURED_RESUME, STRUCTURED_RESUME.strip()));
    }

    @Test
    void aChunkStartingMidSectionInheritsTheSectionOpenBeforeIt() {
        String chunk = "负责支付对账\n项目经历\n对账平台：文件解析与差错工单";

        assertEquals("工作经历/项目", TextChunker.describeSections(STRUCTURED_RESUME, chunk));
    }

    @Test
    void aChunkStartingAtAHeaderDoesNotInheritThePreviousSection() {
        assertEquals("项目", TextChunker.describeSections(STRUCTURED_RESUME, "项目经历\n对账平台：文件解析与差错工单"));
        assertEquals("教育/技能", TextChunker.describeSections(STRUCTURED_RESUME, "教育背景\n某大学 计算机 本科\n专业技能\nJava、Spring Boot、MySQL"));
    }

    @Test
    void fallsBackToTheChunkOnlyGuessWhenTheDocumentDoesNotHelp() {
        // No headers anywhere: nothing marks a preamble as personal details.
        assertEquals(TextChunker.detectSection("负责项目的接口设计"),
                TextChunker.describeSections("负责项目的接口设计与联调", "负责项目的接口设计"));
        // Chunk built from an older version of the text.
        assertEquals("技能", TextChunker.describeSections(STRUCTURED_RESUME, "技能\nGo、Kubernetes"));
    }

    @Test
    void theDemoResumeFirstChunkIsNoLongerLabelledEducation() throws Exception {
        // Regression: this chunk opens with 姓名 and is mostly skills and work experience, but contains the
        // 教育背景 header, so it used to be labelled "教育" and the assistant cited it as the education section.
        String resume = Files.readString(resolveRepoRoot()
                .resolve("experiments/threshold-sweep/dataset/resumes/java-backend-chen.txt"));
        List<String> chunks = new TextChunker(new RagProperties()).split(resume);

        assertEquals("教育", TextChunker.detectSection(chunks.get(0)));
        String label = TextChunker.describeSections(resume, chunks.get(0));
        assertTrue(label.startsWith("基本信息/教育/技能/工作经历"), label);
        for (int i = 1; i < chunks.size(); i++) {
            assertFalse(TextChunker.describeSections(resume, chunks.get(i)).isBlank());
        }
    }

    @Test
    void identicalChunksInDifferentSectionsUseTheirActualPositions() {
        String repeated = "参与 Java 接口开发、MySQL 查询优化及自动化测试。\n".repeat(100);
        String document = "专业技能\n" + repeated + "项目经历\n" + repeated;
        int projectStart = document.indexOf("项目经历");
        List<TextChunker.PositionedChunk> chunks = new TextChunker(new RagProperties()).splitWithPositions(document);
        List<TextChunker.PositionedChunk> projectChunks = chunks.stream()
                .filter(chunk -> chunk.startOffset() > projectStart && !chunk.content().contains("项目经历"))
                .toList();

        assertFalse(projectChunks.isEmpty());
        for (TextChunker.PositionedChunk chunk : projectChunks) {
            assertTrue(document.indexOf(chunk.content()) < projectStart, "the same text must also occur in the skills section");
            assertEquals("项目", TextChunker.describeSections(document, chunk));
        }
    }

    @Test
    void positionsPreserveExistingChunkTextWithCrLfWhitespaceAndOverlap() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(160);
        properties.setChunkOverlap(20);
        TextChunker chunker = new TextChunker(properties);
        String text = "  技能\r\n" + "a".repeat(150) + "\r\n   " + "b".repeat(150)
                + "\r\n项目经历\r\n" + "c".repeat(90) + " ";
        String normalized = text.replace("\r\n", "\n").trim();
        List<String> expected = List.of("技能\n" + "a".repeat(150),
                "a".repeat(16) + "\n   " + "b".repeat(140),
                "b".repeat(30) + "\n项目经历\n" + "c".repeat(90));
        List<TextChunker.PositionedChunk> positioned = chunker.splitWithPositions(text);

        assertEquals(expected, chunker.split(text));
        assertEquals(expected, positioned.stream().map(TextChunker.PositionedChunk::content).toList());
        assertEquals(List.of(0, 137, 277), positioned.stream().map(TextChunker.PositionedChunk::startOffset).toList());
        for (TextChunker.PositionedChunk chunk : positioned) {
            assertEquals(chunk.content(), normalized.substring(chunk.startOffset(), chunk.startOffset() + chunk.content().length()));
        }
        assertEquals("技能/项目", TextChunker.describeSections(text, positioned.get(2)));
    }

    @Test
    void aPositionFromDifferentTextFallsBackInsteadOfReusingAnotherOccurrence() {
        TextChunker.PositionedChunk chunk = new TextChunker.PositionedChunk(2, "Java Spring Boot");

        assertEquals("正文", TextChunker.describeSections("技能\nJava Spring Boot", chunk));
    }

    private static Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("experiments/threshold-sweep/dataset/resumes"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot resolve repository root from " + current);
    }

    @Test
    void defaultChunkSizeIsNotForcedByFalse512Limit() {
        RagProperties properties = new RagProperties();
        // gte-multilingual-base supports 8192 tokens; ~900 chars is intentional.
        assertEquals(900, properties.getChunkSize());
        assertEquals(8192, properties.getMaxLength());
        assertEquals("cls", properties.getPoolingMode());
        assertTrue(properties.getMinSimilarity() > 0);
    }
}


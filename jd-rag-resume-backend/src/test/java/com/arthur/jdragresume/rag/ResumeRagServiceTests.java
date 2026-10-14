package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.entity.ResumeChunk;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeRagServiceTests {
    @TempDir Path tempDir;

    @Test
    void retrievalUsesChunkIndexToLabelRepeatedEvidenceIncludingCachedChunks() throws Exception {
        RagProperties properties = new RagProperties();
        properties.setHybridEnabled(false);
        TextChunker chunker = new TextChunker(properties);
        String repeated = "参与 Java 接口开发、MySQL 查询优化及自动化测试。\n".repeat(100);
        String document = "专业技能\n" + repeated + "项目经历\n" + repeated;
        int projectStart = document.indexOf("项目经历");
        List<TextChunker.PositionedChunk> positions = chunker.splitWithPositions(document);
        List<ResumeChunk> stored = new ArrayList<>();
        ResumeChunkRepository repository = (ResumeChunkRepository) Proxy.newProxyInstance(
                ResumeChunkRepository.class.getClassLoader(), new Class<?>[]{ResumeChunkRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByResumeIdOrderByChunkIndexAsc" -> List.copyOf(stored);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        int[] embeddedTexts = {0};
        EmbeddingModel embeddings = (EmbeddingModel) Proxy.newProxyInstance(
                EmbeddingModel.class.getClassLoader(), new Class<?>[]{EmbeddingModel.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("embed")) throw new UnsupportedOperationException(method.getName());
                    List<?> texts = (List<?>) args[0];
                    embeddedTexts[0] += texts.size();
                    return texts.stream().map(ignored -> new float[]{1, 0}).toList();
                });
        ResumeIndexStore store = new ResumeIndexStore(repository) {
            @Override
            public List<ResumeChunk> replace(Long resumeId, List<ResumeChunk> replacements) {
                stored.clear();
                stored.addAll(replacements);
                return List.copyOf(stored);
            }
        };
        AppUser user = new AppUser();
        Resume resume = new Resume();
        ReflectionTestUtils.setField(resume, "id", 10L);
        resume.setUser(user);
        resume.setRawText(document);
        JobDescription job = new JobDescription();
        job.setTitle("Java 后端");
        job.setDescription("开发后端接口");
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            ResumeRagService service = new ResumeRagService(embeddings, repository, chunker, properties, mapper, store, index);
            List<RetrievedChunk> first = service.retrieve(user, resume, job);
            int beforeCachedRead = embeddedTexts[0];
            List<RetrievedChunk> cached = service.retrieve(user, resume, job);

            assertEquals(1, embeddedTexts[0] - beforeCachedRead, "cached retrieval embeds only the JD, not the resume again");
            assertEquals(first, cached);
            int checked = 0;
            for (RetrievedChunk chunk : cached) {
                TextChunker.PositionedChunk position = positions.get(chunk.chunkIndex());
                if (position.startOffset() > projectStart && !chunk.content().contains("项目经历")) {
                    assertTrue(document.indexOf(chunk.content()) < projectStart);
                    assertEquals("项目", chunk.section());
                    checked++;
                }
            }
            assertTrue(checked > 0);
        }
    }

    @Test
    void calculatesCosineSimilarity() {
        assertEquals(1.0, ResumeRagService.cosineSimilarity(
                new float[]{1.0f, 2.0f},
                new float[]{1.0f, 2.0f}
        ), 0.000001);
        assertEquals(0.0, ResumeRagService.cosineSimilarity(
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        ), 0.000001);
    }

    @Test
    void rejectsMismatchedVectorDimensions() {
        assertEquals(0.0, ResumeRagService.cosineSimilarity(
                new float[]{1.0f},
                new float[]{1.0f, 2.0f}
        ));
    }

    @Test
    void detectSectionFromChunkHeadings() {
        assertEquals("技能", TextChunker.detectSection("技能\nJava Spring Boot MySQL"));
        assertEquals("项目", TextChunker.detectSection("项目经历\n负责 RAG 模块"));
        assertTrue(TextChunker.detectSection("普通正文没有标题").length() > 0);
    }

    @Test
    void computesDeterministicHardSkillCoverageAndScoreCap() {
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("Java 后端工程师");
        jd.setDescription("使用 Spring Boot 与 Redis 开发服务");
        jd.setRequirements("熟悉 MySQL 和 Docker");
        List<RetrievedChunk> evidence = List.of(new RetrievedChunk(
                0,
                "熟悉 Java、Spring Boot、MySQL",
                0.8,
                0.8,
                true,
                "kept",
                "技能",
                List.of()
        ));

        HardSkillCoverage coverage = service.assessHardSkills(jd, evidence);

        assertEquals(List.of("Docker", "Redis"), coverage.missing().stream().sorted().toList());
        assertEquals(3, coverage.matched().size());
        assertEquals("80.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void matchesAsciiSkillsAdjacentToChineseCharacters() {
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("Java后端工程师");
        jd.setDescription("熟悉SpringBoot和Redis");
        jd.setRequirements("了解Docker部署");
        List<RetrievedChunk> evidence = List.of(new RetrievedChunk(
                0,
                "熟悉Java开发，用过SpringBoot、Redis",
                0.8,
                0.8,
                true,
                "kept",
                "技能",
                List.of()
        ));

        HardSkillCoverage coverage = service.assessHardSkills(jd, evidence);

        assertEquals(
                List.of("Docker", "Java", "Redis", "Spring Boot"),
                coverage.required().stream().sorted().toList()
        );
        assertEquals(
                List.of("Java", "Redis", "Spring Boot"),
                coverage.matched().stream().sorted().toList()
        );
        assertEquals(List.of("Docker"), coverage.missing());
    }

    @Test
    void reportsSkillsInDictionaryOrderRegardlessOfJvmHashSeed() {
        // 这些列表会原样写进 LLM prompt 和「规则校验缺失」文案。Map.copyOf 的迭代顺序
        // 随每次 JVM 启动的随机种子变化，同样的输入换一次进程就得到不同的 prompt。
        // 8 个技能在 JD 里故意打乱写；若顺序随机，偶然通过的概率只有 1/8!。
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("后端工程师");
        jd.setDescription("LLM、Git、Redis、Java、Docker、Python、MySQL、Kafka");
        jd.setRequirements("");
        List<RetrievedChunk> evidence = List.of(new RetrievedChunk(
                0,
                "熟悉 Kafka、Java、Git、MySQL",
                0.8,
                0.8,
                true,
                "kept",
                "技能",
                List.of()
        ));

        HardSkillCoverage coverage = service.assessHardSkills(jd, evidence);

        assertEquals(
                List.of("Java", "MySQL", "Redis", "Kafka", "Docker", "Python", "Git", "LLM"),
                coverage.required()
        );
        assertEquals(List.of("Java", "MySQL", "Kafka", "Git"), coverage.matched());
        assertEquals(List.of("Redis", "Docker", "Python", "LLM"), coverage.missing());
    }

    @Test
    void doesNotMatchSkillNameInsideLongerAsciiWord() {
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("javascript工程师");
        jd.setDescription("精通javascript");
        jd.setRequirements("");

        HardSkillCoverage coverage = service.assessHardSkills(jd, List.of());

        assertEquals(List.of("JavaScript"), coverage.required());
    }
}

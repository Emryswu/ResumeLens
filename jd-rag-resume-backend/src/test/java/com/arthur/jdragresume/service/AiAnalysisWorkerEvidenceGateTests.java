package com.arthur.jdragresume.service;

import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.ai.AiProperties;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.rag.HardSkillCoverage;
import com.arthur.jdragresume.rag.RagProperties;
import com.arthur.jdragresume.rag.ResumeRagService;
import com.arthur.jdragresume.rag.RetrievedChunk;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnalysisWorkerEvidenceGateTests {

    @Test
    void completesWithZeroScoreWithoutCallingLlmWhenNoEvidencePassesThreshold() {
        AnalysisHistory history = pendingHistory();
        AnalysisHistoryRepository repository = historyRepository(history);
        CountingAiClient aiClient = new CountingAiClient(null);
        RagProperties ragProperties = new RagProperties();
        StubRagService ragService = new StubRagService(
                ragProperties,
                List.of(),
                new HardSkillCoverage(List.of("Java", "MySQL"), List.of(), List.of("Java", "MySQL"))
        );
        AiAnalysisWorker worker = new AiAnalysisWorker(
                aiClient,
                new AnalysisResultParser(new ObjectMapper()),
                repository,
                new AnalysisHistoryUpdateService(repository, AnalysisHistoryUpdateServiceTests.refundRepository(new ArrayList<>())),
                ragService,
                ragProperties
        );

        worker.process(history.getId());

        assertEquals(0, aiClient.calls());
        assertEquals(AnalysisStatus.COMPLETED, history.getStatus());
        assertEquals(new BigDecimal("0.00"), history.getMatchScore());
        assertTrue(history.getSummary().contains("未检索到达到相似度阈值"));
        assertTrue(history.getStrengths().contains("未发现"));
        assertTrue(history.getMissingSkills().contains("Java"));
        assertTrue(history.getMissingSkills().contains("MySQL"));
        assertTrue(history.getImprovementSuggestions().contains("重新分析"));
        assertTrue(history.getInterviewQuestions().contains("暂无"));
        assertTrue(history.getRetrievedContext().contains("kept=0"));
    }

    @Test
    void stillCallsLlmAndStoresItsResultWhenEvidenceExists() {
        AnalysisHistory history = pendingHistory();
        AnalysisHistoryRepository repository = historyRepository(history);
        CountingAiClient aiClient = new CountingAiClient("""
                {
                  "matchScore": 82.50,
                  "strengths": "具备 Java 项目证据。[chunk-0]",
                  "missingSkills": "尚未体现部署经验。",
                  "improvementSuggestions": "补充上线指标。",
                  "interviewQuestions": "说明项目中的事务边界。",
                  "summary": "Java 证据与岗位相关。"
                }
                """);
        RagProperties ragProperties = new RagProperties();
        RetrievedChunk evidence = new RetrievedChunk(
                0,
                "Java Spring Boot MySQL 项目",
                0.82,
                0.80,
                true,
                "kept",
                "项目",
                List.of("Java")
        );
        StubRagService ragService = new StubRagService(
                ragProperties,
                List.of(evidence),
                new HardSkillCoverage(List.of("Java"), List.of("Java"), List.of())
        );
        AiAnalysisWorker worker = new AiAnalysisWorker(
                aiClient,
                new AnalysisResultParser(new ObjectMapper()),
                repository,
                new AnalysisHistoryUpdateService(repository, AnalysisHistoryUpdateServiceTests.refundRepository(new ArrayList<>())),
                ragService,
                ragProperties
        );

        worker.process(history.getId());

        assertEquals(1, aiClient.calls());
        assertEquals(AnalysisStatus.COMPLETED, history.getStatus());
        assertEquals(new BigDecimal("82.50"), history.getMatchScore());
        assertTrue(history.getStrengths().contains("[chunk-0]"));
        assertEquals("Java 证据与岗位相关。", history.getSummary());
    }

    @Test
    void evidenceHeadersInThePromptUseTheSameTokenTheParserAccepts() {
        AnalysisHistory history = pendingHistory();
        AnalysisHistoryRepository repository = historyRepository(history);
        CountingAiClient aiClient = new CountingAiClient("""
                {"matchScore": 80, "strengths": "具备 Java 项目证据。[chunk-3]", "summary": "ok"}
                """);
        RagProperties ragProperties = new RagProperties();
        RetrievedChunk evidence = new RetrievedChunk(3, "Java Spring Boot 项目", 0.82, 0.80, true, "kept", "项目",
                List.of("Java"));
        AiAnalysisWorker worker = new AiAnalysisWorker(
                aiClient,
                new AnalysisResultParser(new ObjectMapper()),
                repository,
                new AnalysisHistoryUpdateService(repository, AnalysisHistoryUpdateServiceTests.refundRepository(new ArrayList<>())),
                new StubRagService(ragProperties, List.of(evidence),
                        new HardSkillCoverage(List.of("Java"), List.of("Java"), List.of())),
                ragProperties
        );

        worker.process(history.getId());

        // A model that copies the header verbatim must end up with a citation the parser accepts.
        assertTrue(aiClient.lastUserPrompt().contains("\n[chunk-3] similarity="), aiClient.lastUserPrompt());
        assertTrue(!aiClient.lastUserPrompt().contains("resume-chunk"), aiClient.lastUserPrompt());
        assertTrue(aiClient.lastSystemPrompt().contains("[chunk-N]"));
        assertEquals(AnalysisStatus.COMPLETED, history.getStatus());
    }

    private static AnalysisHistory pendingHistory() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        user.setEmail("arthur@example.com");
        user.setDisplayName("Arthur");
        user.setPasswordHash("not-used");

        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("测试简历");
        resume.setCandidateName("候选人");
        resume.setRawText("与岗位无关的简历文本");

        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle("Java 后端工程师");
        job.setCompanyName("测试公司");
        job.setDescription("负责后端开发");
        job.setRequirements("要求 Java 与 MySQL");

        AnalysisHistory history = new AnalysisHistory();
        ReflectionTestUtils.setField(history, "id", 7L);
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(job);
        history.setResumeFingerprint(ContentFingerprints.resume(resume));
        history.setJobFingerprint(ContentFingerprints.job(job));
        history.setStatus(AnalysisStatus.PENDING);
        return history;
    }

    private static AnalysisHistoryRepository historyRepository(AnalysisHistory history) {
        return (AnalysisHistoryRepository) Proxy.newProxyInstance(
                AnalysisHistoryRepository.class.getClassLoader(),
                new Class<?>[]{AnalysisHistoryRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findWithDetailsById", "findByIdForUpdate" -> Optional.of(history);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "AnalysisHistoryRepositoryEvidenceGateTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static final class CountingAiClient extends AiClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final String response;
        private String lastSystemPrompt;
        private String lastUserPrompt;

        private CountingAiClient(String response) {
            super(new AiProperties(), new ObjectMapper());
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userPrompt;
            if (response == null) {
                throw new AssertionError("LLM must not be called for zero evidence");
            }
            return response;
        }

        int calls() {
            return calls.get();
        }

        String lastSystemPrompt() {
            return lastSystemPrompt;
        }

        String lastUserPrompt() {
            return lastUserPrompt;
        }
    }

    private static final class StubRagService extends ResumeRagService {
        private final List<RetrievedChunk> chunks;
        private final HardSkillCoverage coverage;

        private StubRagService(
                RagProperties properties,
                List<RetrievedChunk> chunks,
                HardSkillCoverage coverage
        ) {
            super(null, null, null, properties, new ObjectMapper(), null, null);
            this.chunks = chunks;
            this.coverage = coverage;
        }

        @Override
        public List<RetrievedChunk> retrieve(AppUser user, Resume resume, JobDescription jobDescription) {
            return chunks;
        }

        @Override
        public HardSkillCoverage assessHardSkills(
                JobDescription jobDescription,
                List<RetrievedChunk> keptChunks
        ) {
            return coverage;
        }
    }
}

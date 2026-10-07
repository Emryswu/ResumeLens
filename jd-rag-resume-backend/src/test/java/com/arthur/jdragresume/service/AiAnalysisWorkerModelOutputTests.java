package com.arthur.jdragresume.service;

import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.ai.AiProperties;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AnalysisSubmissionRefund;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the worker does with model output that does not fully hold up, and what the user gets to see. */
@ExtendWith(OutputCaptureExtension.class)
class AiAnalysisWorkerModelOutputTests {
    private static final long SUBMISSION_LOG_ID = 41L;

    private final AnalysisHistory history = pendingHistory();
    private final List<AnalysisSubmissionRefund> refunds = new ArrayList<>();
    private String finishReason = "stop";
    private Integer completionTokens = 400;

    @Test
    void partiallyInvalidCitationsAreDroppedAndTheReportStillCompletes() {
        run(() -> response("具备 Java 项目证据。[chunk-0]\\n熟悉 Kafka。[chunk-5]\\n有 MySQL 调优经验。[chunk-1]"));

        assertEquals(AnalysisStatus.COMPLETED, history.getStatus());
        assertEquals("具备 Java 项目证据。[chunk-0]\n有 MySQL 调优经验。[chunk-1]", history.getStrengths());
        assertEquals("Java 证据与岗位相关。", history.getSummary());
        assertTrue(refunds.isEmpty());
    }

    @Test
    void everyCitationInvalidFailsWithAFriendlyMessageAndRefundsTheSubmission() {
        run(() -> response("熟悉 Kafka。[chunk-5]\\n熟悉 Redis。"));

        assertEquals(AnalysisStatus.FAILED, history.getStatus());
        assertEquals("分析结果校验未通过，请稍后重试", history.getSummary());
        assertEquals(1, refunds.size());
        assertEquals(SUBMISSION_LOG_ID, refunds.getFirst().getSubmissionLogId());
        assertEquals("AI_RESPONSE_CITATION_INVALID", refunds.getFirst().getReason());
    }

    @Test
    void responseThatIsNotAnalysisJsonIsAlsoRefunded() {
        run(() -> "抱歉，我无法完成这个请求。");

        assertEquals(AnalysisStatus.FAILED, history.getStatus());
        assertEquals("分析结果校验未通过，请稍后重试", history.getSummary());
        assertEquals(1, refunds.size());
        assertEquals("AI_RESPONSE_PARSE_FAILED", refunds.getFirst().getReason());
    }

    @Test
    void aReplyCutOffAtMaxTokensIsLoggedWithItsFinishReasonAndFailureButNoText(CapturedOutput output) {
        finishReason = "length";
        completionTokens = AiClient.ANALYSIS_MAX_TOKENS;
        run(() -> "{\"matchScore\": 80, \"strengths\": \"候选人张三 电话 13800001101，熟悉 Ja");

        assertEquals(AnalysisStatus.FAILED, history.getStatus());
        assertEquals("分析结果校验未通过，请稍后重试", history.getSummary());
        assertEquals("AI_RESPONSE_PARSE_FAILED", refunds.getFirst().getReason());
        assertTrue(lineWith(output, "AI analysis 11 completion finishReason=length promptTokens=1800 "
                + "completionTokens=1200 maxTokens=1200 contentChars=").contains(" WARN "), output.getOut());
        assertTrue(output.getOut().contains("AI analysis 11 rejected: model response is not valid analysis JSON: "
                + "failure=TRUNCATED_JSON"), output.getOut());
        assertFalse(output.getOut().contains("13800001101"));
        assertFalse(output.getOut().contains("张三"));
    }

    @Test
    void aCompleteReplyIsLoggedAtInfoWithItsUsage(CapturedOutput output) {
        run(() -> response("具备 Java 项目证据。[chunk-0]"));

        assertEquals(AnalysisStatus.COMPLETED, history.getStatus());
        assertTrue(lineWith(output, "AI analysis 11 completion finishReason=stop promptTokens=1800 "
                + "completionTokens=400 maxTokens=1200 contentChars=").contains(" INFO "), output.getOut());
    }

    private static String lineWith(CapturedOutput output, String fragment) {
        return output.getOut().lines().filter(line -> line.contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no log line with: " + fragment + "\n" + output.getOut()));
    }

    @Test
    void otherFailuresShowAGenericMessageWithoutExceptionDetailsAndAreNotRefunded() {
        run(() -> {
            throw new IllegalStateException("upstream timed out after 60s at https://api.example.com");
        });

        assertEquals(AnalysisStatus.FAILED, history.getStatus());
        assertEquals("AI 分析失败，请稍后重试", history.getSummary());
        assertFalse(history.getSummary().contains("Exception"));
        assertFalse(history.getSummary().contains("timed out"));
        assertTrue(refunds.isEmpty());
    }

    private void run(Supplier<String> modelReply) {
        RagProperties ragProperties = new RagProperties();
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk(0, "Java Spring Boot 项目", 0.82, 0.80, true, "kept", "项目", List.of("Java")),
                new RetrievedChunk(1, "MySQL 慢查询优化", 0.78, 0.76, true, "kept", "项目", List.of("MySQL")),
                new RetrievedChunk(5, "兴趣爱好", 0.40, 0.40, false, "below-threshold", "其他", List.of())
        );
        AnalysisHistoryRepository repository = (AnalysisHistoryRepository) Proxy.newProxyInstance(
                AnalysisHistoryRepository.class.getClassLoader(),
                new Class<?>[]{AnalysisHistoryRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findWithDetailsById", "findByIdForUpdate" -> Optional.of(history);
                    case "toString" -> "AnalysisHistoryRepositoryModelOutputTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        AiAnalysisWorker worker = new AiAnalysisWorker(
                new AiClient(new AiProperties(), new ObjectMapper()) {
                    @Override
                    public Completion complete(String systemPrompt, String userPrompt) {
                        return new Completion(modelReply.get(), finishReason, 1800, completionTokens);
                    }
                },
                new AnalysisResultParser(new ObjectMapper()),
                repository,
                new AnalysisHistoryUpdateService(repository, AnalysisHistoryUpdateServiceTests.refundRepository(refunds)),
                new ResumeRagService(null, null, null, ragProperties, new ObjectMapper(), null, null) {
                    @Override
                    public List<RetrievedChunk> retrieve(AppUser user, Resume resume, JobDescription jobDescription) {
                        return chunks;
                    }

                    @Override
                    public HardSkillCoverage assessHardSkills(JobDescription jobDescription, List<RetrievedChunk> kept) {
                        return new HardSkillCoverage(List.of("Java"), List.of("Java"), List.of());
                    }
                },
                ragProperties
        );

        worker.process(history.getId());
    }

    private static String response(String strengths) {
        return """
                {
                  "matchScore": 82.50,
                  "strengths": "%s",
                  "missingSkills": "尚未体现部署经验。",
                  "improvementSuggestions": "补充上线指标。",
                  "interviewQuestions": "说明项目中的事务边界。",
                  "summary": "Java 证据与岗位相关。"
                }
                """.formatted(strengths);
    }

    private static AnalysisHistory pendingHistory() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("测试简历");
        resume.setRawText("Java Spring Boot MySQL");
        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle("Java 后端工程师");
        job.setCompanyName("测试公司");
        job.setDescription("负责后端开发");
        job.setRequirements("要求 Java");

        AnalysisHistory history = new AnalysisHistory();
        ReflectionTestUtils.setField(history, "id", 11L);
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(job);
        history.setResumeFingerprint(ContentFingerprints.resume(resume));
        history.setJobFingerprint(ContentFingerprints.job(job));
        history.setSubmissionLogId(SUBMISSION_LOG_ID);
        history.setStatus(AnalysisStatus.PENDING);
        return history;
    }
}

package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.dto.analysis.AnalysisHistorySummaryResponse;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.service.AiAnalysisService;
import com.arthur.jdragresume.service.AnalysisHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisHistoryControllerTests {
    private MockMvc mockMvc;
    private RecordingHistoryService historyService;
    private RecordingAiService aiService;

    @BeforeEach
    void setUp() {
        historyService = new RecordingHistoryService();
        aiService = new RecordingAiService();
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalysisHistoryController(historyService, aiService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    void postCollectionIsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/analysis-histories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeId":2,"jobDescriptionId":3,"summary":"client supplied"}
                                """))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(0, aiService.analyzeCalls.get());
        assertEquals(0, historyService.deleteCalls.get());
    }

    @Test
    void putByIdIsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/analysis-histories/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeId":2,"jobDescriptionId":3,"summary":"edited summary"}
                                """))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(0, aiService.analyzeCalls.get());
    }

    @Test
    void postAiStillDelegatesToAnalysisService() throws Exception {
        aiService.response = sample(9L, AnalysisStatus.PENDING);
        mockMvc.perform(post("/api/analysis-histories/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeId":2,"jobDescriptionId":3}
                                """))
                .andExpect(status().isCreated());
        assertEquals(1, aiService.analyzeCalls.get());
        assertEquals(new AiAnalysisRequest(2L, 3L), aiService.lastRequest.get());
    }

    @Test
    void postAiPropagatesSubmitGuardRateLimit() throws Exception {
        aiService.failure = new BusinessException("ANALYSIS_RATE_LIMITED", "too many analysis requests");
        mockMvc.perform(post("/api/analysis-histories/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeId":2,"jobDescriptionId":3}
                                """))
                .andExpect(status().isTooManyRequests());
        assertEquals(1, aiService.analyzeCalls.get());
    }

    @Test
    void getByIdAndLatestStillWork() throws Exception {
        historyService.byId = sample(11L, AnalysisStatus.COMPLETED);
        historyService.latest = Optional.of(sample(12L, AnalysisStatus.PENDING));

        mockMvc.perform(get("/api/analysis-histories/11"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/analysis-histories/latest")
                        .param("resumeId", "2")
                        .param("jobDescriptionId", "3"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/analysis-histories/latest-by-resume")
                        .param("resumeId", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/analysis-histories")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk());

        assertEquals(11L, historyService.foundId.get());
        assertEquals(2L, historyService.latestResumeId.get());
        assertEquals(3L, historyService.latestJobId.get());
        assertEquals(2L, historyService.latestByResumeId.get());
        assertEquals(1, historyService.findAllCalls.get());
        assertEquals(0, aiService.analyzeCalls.get());
    }

    @Test
    void deleteByIdRemainsAvailable() throws Exception {
        mockMvc.perform(delete("/api/analysis-histories/11"))
                .andExpect(status().isOk());
        assertEquals(11L, historyService.deletedId.get());
    }

    private static AnalysisHistoryResponse sample(Long id, AnalysisStatus status) {
        LocalDateTime now = LocalDateTime.parse("2026-08-30T00:00:00");
        return new AnalysisHistoryResponse(
                id, 1L, "arthur", 2L, "resume", 3L, "job",
                status == AnalysisStatus.COMPLETED ? new BigDecimal("80.00") : null,
                status, "summary", null, null, null, null, null, null, null, now, now
        );
    }

    private static final class RecordingHistoryService extends AnalysisHistoryService {
        private AnalysisHistoryResponse byId;
        private Optional<AnalysisHistoryResponse> latest = Optional.empty();
        private final AtomicReference<Long> foundId = new AtomicReference<>();
        private final AtomicReference<Long> latestResumeId = new AtomicReference<>();
        private final AtomicReference<Long> latestJobId = new AtomicReference<>();
        private final AtomicReference<Long> latestByResumeId = new AtomicReference<>();
        private final AtomicReference<Long> deletedId = new AtomicReference<>();
        private final AtomicInteger findAllCalls = new AtomicInteger();
        private final AtomicInteger deleteCalls = new AtomicInteger();

        private RecordingHistoryService() {
            super(null, null, null, null);
        }

        @Override
        public PageResponse<AnalysisHistoryResponse> findAll(int page, int size, String keyword) {
            findAllCalls.incrementAndGet();
            return new PageResponse<>(List.of(), 0, size, 0, 0, true, true);
        }

        @Override
        public AnalysisHistoryResponse findById(Long id) {
            foundId.set(id);
            return byId;
        }

        @Override
        public Optional<AnalysisHistoryResponse> findLatest(Long resumeId, Long jobDescriptionId) {
            latestResumeId.set(resumeId);
            latestJobId.set(jobDescriptionId);
            return latest;
        }

        @Override
        public List<AnalysisHistorySummaryResponse> findLatestForEachJob(Long resumeId) {
            latestByResumeId.set(resumeId);
            return List.of();
        }

        @Override
        public void delete(Long id) {
            deleteCalls.incrementAndGet();
            deletedId.set(id);
        }
    }

    private static final class RecordingAiService extends AiAnalysisService {
        private final AtomicInteger analyzeCalls = new AtomicInteger();
        private final AtomicReference<AiAnalysisRequest> lastRequest = new AtomicReference<>();
        private AnalysisHistoryResponse response;
        private BusinessException failure;

        private RecordingAiService() {
            super(null, null, null, null, null);
        }

        @Override
        public AnalysisHistoryResponse analyze(AiAnalysisRequest request) {
            analyzeCalls.incrementAndGet();
            lastRequest.set(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}

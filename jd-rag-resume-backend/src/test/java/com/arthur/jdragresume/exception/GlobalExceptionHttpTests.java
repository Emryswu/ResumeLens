package com.arthur.jdragresume.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.arthur.jdragresume.controller.JobDescriptionController;
import com.arthur.jdragresume.controller.ResumeController;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSemanticMatchService;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class GlobalExceptionHttpTests {
    private static final String SAFE_MESSAGE = "invalid request";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private MockMvc mvc;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        ResumeService resumes = mock(ResumeService.class);
        doThrow(new MaxUploadSizeExceededException(1024))
                .when(resumes).upload(any(MultipartFile.class), nullable(String.class), eq("Arthur"),
                        nullable(String.class), nullable(String.class));
        mvc = MockMvcBuilders.standaloneSetup(
                        new ResumeController(resumes),
                        new JobDescriptionController(mock(JobDescriptionService.class),
                                mock(JobSemanticMatchService.class)),
                        new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logs);
        logs.stop();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("missing required query parameter",
                        get("/api/job-descriptions/source").param("sourcePlatform", "BOSS"), 400),
                Arguments.of("JSON API with text/plain content type",
                        post("/api/resumes").contentType(MediaType.TEXT_PLAIN)
                                .content("{\"title\":\"cv\"}"), 415),
                Arguments.of("multipart upload without file",
                        multipart("/api/resumes/upload").param("candidateName", "Arthur"), 400),
                Arguments.of("multipart upload exceeds size limit",
                        multipart("/api/resumes/upload")
                                .file(new MockMultipartFile("file", "cv.txt", "text/plain",
                                        "sample".getBytes(StandardCharsets.UTF_8)))
                                .param("candidateName", "Arthur"), 413),
                Arguments.of("malformed JSON request body",
                        post("/api/resumes").contentType(MediaType.APPLICATION_JSON).content("{"), 400),
                Arguments.of("invalid path variable type",
                        get("/api/resumes/java.lang.IllegalStateException"), 400),
                Arguments.of("missing validated JSON fields",
                        post("/api/resumes").contentType(MediaType.APPLICATION_JSON).content("{}"), 400)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void requestErrorsKeepHttpStatusAndDoNotLeakInternals(String scenario, RequestBuilder request, int expected)
            throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), scenario);
        JsonNode json = mapper.readTree(response.getContentAsString());
        assertEquals(SAFE_MESSAGE, json.path("message").asText(), scenario);
        assertTrue(json.path("code").isTextual(), scenario);
        for (String internal : List.of("java.", "com.arthur", "JSON parse error", "ResumeController",
                "MethodArgument", "handleBadRequest", "readValue")) {
            assertFalse(json.path("message").asText().contains(internal), scenario + ": " + internal);
        }
        assertTrue(logs.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)),
                scenario + ": unexpected ERROR log");
    }

    @Test
    void businessValidationErrorsAreAlsoSafe() throws Exception {
        var response = mvc.perform(get("/test/validation")).andReturn().getResponse();
        assertEquals(400, response.getStatus());
        assertEquals("请先上传一份简历", mapper.readTree(response.getContentAsString()).path("message").asText());
        assertTrue(logs.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)));
    }

    @Test
    void internalIllegalArgumentIsNotTreatedAsAClientErrorAndLogHasNoThrowable() throws Exception {
        var response = mvc.perform(get("/test/internal-argument")).andReturn().getResponse();
        assertEquals(500, response.getStatus());
        assertEquals("unexpected server error", mapper.readTree(response.getContentAsString())
                .path("message").asText());
        List<ILoggingEvent> errors = logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
        assertEquals(1, errors.size());
        assertNull(errors.getFirst().getThrowableProxy());
        assertTrue(errors.getFirst().getFormattedMessage().contains("type=IllegalArgumentException"));
        assertFalse(errors.getFirst().getFormattedMessage().contains("secret-resume-text"));
    }

    @RestController
    static class FailureController {
        @GetMapping("/test/validation")
        void validation() {
            throw new BusinessException("VALIDATION_ERROR", "请先上传一份简历");
        }

        @GetMapping("/test/internal-argument")
        void internalArgument() {
            throw new IllegalArgumentException("secret-resume-text");
        }
    }
}

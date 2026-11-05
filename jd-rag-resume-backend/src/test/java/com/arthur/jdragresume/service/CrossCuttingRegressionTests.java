package com.arthur.jdragresume.service;

import com.arthur.jdragresume.agent.ConversationStore;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.collect.CollectorMode;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.JwtProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Red on unmodified main: each assertion guards one externally visible regression. */
class CrossCuttingRegressionTests {
    private static final Path MAIN = Path.of("src/main/java/com/arthur/jdragresume");

    private static String source(String path) throws Exception {
        return Files.readString(MAIN.resolve(path));
    }

    @Test
    void uploadMetadataViolationsAreValidationErrorsBeforeWritingFiles(@TempDir Path root) throws Exception {
        ResumeService service = uploadService(root);
        record Case(String candidateName, String phone, String email, String filename, String contentType) {}
        List<Case> cases = List.of(
                new Case("Arthur", "1".repeat(41), null, "resume.txt", "text/plain"),
                new Case("Arthur", null, "a".repeat(130) + "@example.com", "resume.txt", "text/plain"),
                new Case("Arthur", null, "not-an-email", "resume.txt", "text/plain"),
                new Case("A".repeat(81), null, null, "resume.txt", "text/plain"),
                new Case("Arthur", null, null, "x".repeat(256) + ".txt", "text/plain"),
                new Case("Arthur", null, null, "resume.txt", "x".repeat(121))
        );
        for (Case item : cases) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", item.filename(), item.contentType(),
                    "Java Spring Boot backend developer with MySQL, Redis, Docker, REST APIs and testing experience."
                            .getBytes(StandardCharsets.UTF_8));
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> invokeUpload(service, file, "Backend", item.candidateName(), item.phone(), item.email()),
                    "expected rejection: " + item);
            assertEquals("VALIDATION_ERROR", failure.getCode(), item.toString());
            try (Stream<Path> files = Files.walk(root)) {
                assertEquals(0, files.filter(Files::isRegularFile).count(), item.toString());
            }
        }
    }

    private static void invokeUpload(ResumeService service, MockMultipartFile file, String title,
                                     String candidateName, String phone, String email) {
        Method method = Stream.of(ResumeService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("upload")).findFirst().orElseThrow();
        Object[] args = method.getParameterCount() == 6
                ? new Object[]{file, title, candidateName, phone, email, null}
                : new Object[]{file, title, candidateName, phone, email};
        try {
            method.invoke(service, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ResumeService uploadService(Path root) {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setUsername("test");
        ResumeRepository resumes = proxy(ResumeRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "countByUserId", "sumFileSizeByUserId" -> 0L;
            case "save" -> args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AppUserRepository users = proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdForUpdate" -> Optional.of(user);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        CurrentUserService current = new CurrentUserService(users) {
            @Override public AppUser getCurrentUser() { return user; }
        };
        return new ResumeService(resumes, proxy(ResumeChunkRepository.class,
                (ignored, method, args) -> { throw new UnsupportedOperationException(method.getName()); }),
                current, users, new ResumeTextExtractor(new ResumeTextQualityValidator()), root.toString(), null,
                SemanticEmbeddingTestSupport.service(), null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Test
    void uploadCannotAcceptClientSuppliedRawText() throws Exception {
        for (String path : List.of("service/ResumeService.java", "controller/ResumeController.java")) {
            String text = source(path);
            Matcher signature = Pattern.compile("(?s)public\\s+[^\\n]+\\s+upload\\s*\\((.*?)\\)\\s*\\{").matcher(text);
            assertTrue(signature.find(), path);
            assertFalse(signature.group(1).contains("String rawText"), path + " must parse the uploaded file");
        }
    }

    @Test
    void reservationIdentityRemainsUnderDatabaseLimitForUntrustedCallId() {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 1L);
        var run = new ConversationStore.Run(1L, 2L, "turn", "run", null, null, 0L);
        var context = new AgentToolContext(user, run, CollectorMode.AUTO).forCollection(
                "model-id-".repeat(200), Long.MAX_VALUE, event -> {});
        var other = new AgentToolContext(user, run, CollectorMode.AUTO).forCollection(
                "other-id-".repeat(200), Long.MAX_VALUE, event -> {});
        assertNotNull(context.collection().reservationId());
        assertTrue(context.collection().reservationId().length() <= 240);
        assertNotEquals(context.collection().reservationId(), other.collection().reservationId());
    }

    @Test
    void migrationUsesVarcharForAnalysisStatus() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V24__analysis_status_varchar.sql");
        assertTrue(Files.exists(migration), "V24 must align the existing ENUM with the JPA STRING field");
        assertTrue(Pattern.compile("(?is)alter\\s+table\\s+analysis_history.*?status\\s+varchar\\s*\\(\\s*20\\s*\\)")
                .matcher(Files.readString(migration)).find());
    }

    @Test
    void historySearchEscapesWildcardCharacters() throws Exception {
        String repository = source("repository/AnalysisHistoryRepository.java");
        String service = source("service/AnalysisHistoryService.java");
        assertTrue(repository.contains("escape '!'"), "JPQL LIKE must declare its escape character");
        assertTrue(service.contains("LikeKeywords.escapeLikeKeyword(keyword)"), "history must escape user-supplied LIKE input");
    }

    @Test
    void everyRepositoryQueryWithLeadingWildcardHasAnEscapeClause() throws Exception {
        Path dir = MAIN.resolve("repository");
        Pattern query = Pattern.compile("(?s)@Query\\(\\s*\"\"\"(.*?)\"\"\"\\s*\\)");
        Pattern like = Pattern.compile("(?i)\\blike\\s+[^\\n]*concat\\('%'");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = query.matcher(Files.readString(file));
                while (matcher.find()) {
                    for (String line : matcher.group(1).split("\\R")) {
                        if (like.matcher(line).find()) {
                            assertTrue(line.toLowerCase().contains("escape '!'"),
                                    file.getFileName() + " has unescaped LIKE: " + line.strip());
                        }
                    }
                }
            }
        }
    }

    @Test
    void servicesAndSecurityUseInjectedClockExceptConcurrentAuthPr() throws Exception {
        Pattern directNow = Pattern.compile("\\b(?:Instant|LocalDateTime)\\s*\\.\\s*now\\s*\\(\\s*\\)");
        for (String folder : List.of("service", "security")) {
            try (Stream<Path> files = Files.walk(MAIN.resolve(folder))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    // 待账号 PR 合并后移除：RefreshTokenService.java
                    if (file.getFileName().toString().equals("RefreshTokenService.java")) continue;
                    assertFalse(directNow.matcher(Files.readString(file)).find(),
                            file.getFileName() + " must use the injected Clock");
                }
            }
        }
    }

    @Test
    void queueRejectionRefundsItsSubmission() throws Exception {
        String source = source("service/AiAnalysisService.java");
        assertTrue(source.contains("failIfPendingAndRefund("), "queue-full must use atomic fail+refund");
    }

    @Test
    void jwtExpirationsArePositiveAndAccessDurationIsBounded() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setExpirationMinutes(15);
        properties.setRefreshExpirationDays(7);
        assertTrue(validator.validate(properties).isEmpty());
        properties.setExpirationMinutes(0);
        assertFalse(validator.validate(properties).isEmpty());
        properties.setExpirationMinutes(1441);
        assertFalse(validator.validate(properties).isEmpty());
        properties.setExpirationMinutes(15);
        properties.setRefreshExpirationDays(0);
        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void publicHttpsCannotStartWithInsecureRefreshCookie() throws Exception {
        assertTrue(source("security/SecurityConfig.java").contains("CookieSecurity.validateTrustedOrigins("),
                "startup wiring must reject https trusted-origins with refresh-cookie-secure=false");
    }

    @Test
    void securityConfigDoesNotDependOnCollectorClockConfiguration() throws Exception {
        assertFalse(source("security/SecurityConfig.java").contains("BossPageBudgetConfig"),
                "SecurityConfig should not import collect package's Clock configuration");
    }

    @Test
    void aiStatusRequiresAuthenticationAndClientWaitsUntilSignedIn() throws Exception {
        assertFalse(source("security/SecurityConfig.java").contains("\"/api/ai/status\""),
                "AI model status must not be in permitAll matchers");
        String page = Files.readString(Path.of("../jd-rag-resume-frontend/app/page.tsx"));
        assertFalse(page.contains("apiRequest<AiStatus>(\"/api/ai/status\", {}, { auth: false })"),
                "anonymous API request must be removed");
        assertTrue(page.contains("if (status !== \"signedIn\" || userId == null) return;"),
                "the status request effect must wait for a user session");
    }
}

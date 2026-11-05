package com.arthur.jdragresume.common;

import com.arthur.jdragresume.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LogSafeTests {
    private static final String PHONE = "13812345678";
    private static final String EMAIL = "arthur@example.com";
    private static final String MESSAGE = "简历正文 " + PHONE + " " + EMAIL;

    @Test
    void describesTypeCodeOwnFrameAndCauseWithoutAnyMessage() {
        BusinessException ex = failInOwnCode();
        String line = LogSafe.describe(ex);

        assertTrue(line.contains("type=BusinessException"), line);
        assertTrue(line.contains("code=AI_REQUEST_FAILED"), line);
        assertTrue(line.matches(".*at=LogSafeTests\\.failInOwnCode:\\d+.*"), line);
        assertTrue(line.contains("cause=IllegalStateException"), line);
        assertFalse(line.contains(PHONE), line);
        assertFalse(line.contains(EMAIL), line);
        assertFalse(line.contains("简历正文"), line);
        assertFalse(line.contains("cause message"), line);
    }

    @Test
    void plainExceptionsAndFramesOutsideOurCodeStaySafe() {
        RuntimeException ex = new RuntimeException(MESSAGE);
        ex.setStackTrace(new StackTraceElement[] {new StackTraceElement("java.util.Objects", "requireNonNull", null, 1)});
        String line = LogSafe.describe(ex);
        assertEquals("type=RuntimeException at=unknown", line);
        assertEquals("type=null", LogSafe.describe(null));
    }

    @Test
    void ownFrameIsTakenFromTheCauseWhenTheOuterExceptionHasNone() {
        RuntimeException outer = new RuntimeException(MESSAGE, failInOwnCode());
        outer.setStackTrace(new StackTraceElement[0]);
        String line = LogSafe.describe(outer);
        assertTrue(line.matches(".*at=LogSafeTests\\.failInOwnCode:\\d+.*"), line);
        assertFalse(line.contains(PHONE), line);
    }

    /**
     * Exceptions are never logged as objects or messages. Keep this map empty unless a specific
     * production file genuinely requires an exemption; each entry must state the review reason.
     */
    private static final Map<String, String> LOG_EXCEPTION_ALLOWLIST = Map.of();

    @Test
    void allProductionClassesNeverLogExceptionObjectsOrMessages() throws IOException {
        Path root = Path.of("src/main");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (LOG_EXCEPTION_ALLOWLIST.containsKey(relative)) {
                    assertFalse(LOG_EXCEPTION_ALLOWLIST.get(relative).isBlank(),
                            "Whitelist entries require an explicit reason: " + relative);
                    continue;
                }
                violations.addAll(unsafeLogCalls(relative, Files.readString(file)));
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void scanCatchesDirectThrowableArgumentsAndExceptionMessages() {
        String bad = """
                class Example {
                    void run() {
                        try { throw new RuntimeException(); }
                        catch (RuntimeException ex) {
                            log.error("failed {}", ex);
                            log.warn("detail {}", ex.getMessage());
                        }
                    }
                }
                """;
        assertEquals(2, unsafeLogCalls("Example.java", bad).size());

        String safe = """
                class Example {
                    void run() {
                        try { throw new RuntimeException(); }
                        catch (RuntimeException ex) {
                            log.error("failed {}", LogSafe.describe(ex));
                        }
                    }
                }
                """;
        assertEquals(List.of(), unsafeLogCalls("Example.java", safe));
    }

    private static List<String> unsafeLogCalls(String file, String source) {
        Pattern logCall = Pattern.compile(
                "\\b(?:log|logger)\\.(?:trace|debug|info|warn|error)\\((.*?)\\);",
                Pattern.DOTALL);
        // Include caught exceptions and Throwable/Exception-typed method parameters.
        Pattern throwableDeclaration = Pattern.compile(
                "\\b(?:Throwable|\\w*Exception|\\w*Error)\\s+(\\w+)\\b");
        Set<String> throwableNames = new HashSet<>();
        Matcher declarations = throwableDeclaration.matcher(source);
        while (declarations.find()) throwableNames.add(declarations.group(1));

        List<String> violations = new ArrayList<>();
        Matcher calls = logCall.matcher(source);
        while (calls.find()) {
            String args = calls.group(1).replaceAll(
                    "LogSafe\\.describe\\(\\s*\\w+\\s*\\)", "SAFE");
            boolean unsafe = args.contains(".getMessage()") || args.contains(".getLocalizedMessage()")
                    || args.contains(".printStackTrace()");
            for (String name : throwableNames) {
                if (List.of(args.split(",")).stream().map(String::strip).anyMatch(name::equals)
                        || Pattern.compile("\\+\\s*" + Pattern.quote(name) + "\\b|\\b"
                                + Pattern.quote(name) + "\\s*\\+").matcher(args).find()) {
                    unsafe = true;
                }
            }
            if (unsafe) violations.add(file + ": " + calls.group().strip());
        }
        return violations;
    }

    private static BusinessException failInOwnCode() {
        BusinessException ex = new BusinessException("AI_REQUEST_FAILED", MESSAGE);
        ex.initCause(new IllegalStateException("cause message " + PHONE));
        return ex;
    }
}

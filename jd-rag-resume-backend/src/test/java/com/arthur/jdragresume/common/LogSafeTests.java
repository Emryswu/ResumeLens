package com.arthur.jdragresume.common;

import com.arthur.jdragresume.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
     * Classes on the path to the model may only log exceptions through {@link LogSafe#describe}: passing the
     * exception object prints its message and stack, and getMessage() can carry resume text or contact details.
     */
    @Test
    void modelPathClassesNeverLogExceptionObjectsOrMessages() throws IOException {
        Path root = Path.of("src/main/java/com/arthur/jdragresume");
        List<String> files = List.of("agent/AgentLoop.java", "agent/AgentChatService.java",
                "agent/OpenAiCompatibleAgentModel.java", "agent/ShortTermMemory.java",
                "ai/AiClient.java", "service/AiAnalysisWorker.java");
        Pattern logCall = Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);
        Pattern catchVar = Pattern.compile("catch\\s*\\([^)]*?\\s(\\w+)\\s*\\)");
        List<String> violations = new ArrayList<>();
        for (String file : files) {
            String source = Files.readString(root.resolve(file));
            List<String> caught = new ArrayList<>();
            Matcher vars = catchVar.matcher(source);
            while (vars.find()) caught.add(vars.group(1));
            Matcher calls = logCall.matcher(source);
            while (calls.find()) {
                String args = calls.group(1).replaceAll("LogSafe\\.describe\\(\\w+\\)", "SAFE");
                if (args.contains("getMessage") || args.contains("getLocalizedMessage")
                        || args.contains("printStackTrace")) {
                    violations.add(file + ": " + calls.group().strip());
                    continue;
                }
                for (String arg : args.split(",")) {
                    if (caught.contains(arg.strip())) violations.add(file + ": " + calls.group().strip());
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void scanCatchesTheWritingItForbids() {
        Pattern logCall = Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);
        Matcher matcher = logCall.matcher("log.error(\"failed {}\", id,\n        ex);");
        assertTrue(matcher.find());
        assertTrue(List.of(matcher.group(1).split(",")).stream().map(String::strip).anyMatch("ex"::equals));
    }

    private static BusinessException failInOwnCode() {
        BusinessException ex = new BusinessException("AI_REQUEST_FAILED", MESSAGE);
        ex.initCause(new IllegalStateException("cause message " + PHONE));
        return ex;
    }
}

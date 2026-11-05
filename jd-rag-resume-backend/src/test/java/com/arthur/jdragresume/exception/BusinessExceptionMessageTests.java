package com.arthur.jdragresume.exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessExceptionMessageTests {
    private static final Pattern CONSTRUCTOR = Pattern.compile("new\\s+BusinessException\\s*\\(");
    private static final Pattern INTERNAL_TEXT = Pattern.compile("java\\.|com\\.arthur|Exception");
    private static final Pattern EXCEPTION_MESSAGE = Pattern.compile(
            "\\b\\w+\\s*\\.\\s*get(?:Localized)?Message\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    @Test
    void productionBusinessExceptionMessagesDoNotExposeInternalDetails() throws IOException {
        Path root = Path.of("src/main");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                violations.addAll(unsafeMessages(root.relativize(file).toString(),
                        Files.readString(file)));
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void scannerRejectsInternalStringsAndExceptionMessageConcatenation() {
        String examples = """
                new BusinessException("BAD", "java.lang.IllegalStateException");
                new BusinessException("BAD", "com.arthur.Service");
                new BusinessException("BAD", "Exception");
                new BusinessException("BAD", "失败：" + ex.getMessage());
                new BusinessException("BAD", ex.getLocalizedMessage());
                new BusinessException("GOOD", "请重新上传简历");
                """;
        assertEquals(5, unsafeMessages("Example.java", examples).size());
    }

    private static List<String> unsafeMessages(String path, String source) {
        List<String> violations = new ArrayList<>();
        Matcher constructors = CONSTRUCTOR.matcher(source);
        while (constructors.find()) {
            int start = constructors.end();
            int depth = 1;
            int comma = -1;
            int end = start;
            boolean quoted = false;
            boolean escaped = false;
            for (; end < source.length(); end++) {
                char c = source.charAt(end);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') quoted = false;
                    continue;
                }
                if (c == '"') quoted = true;
                else if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
                else if (c == ')' ) depth++;
                else if (c == ',' && depth == 1 && comma < 0) comma = end;
            }
            if (comma < 0 || depth != 0) continue;
            String message = source.substring(comma + 1, end);
            Matcher literals = STRING_LITERAL.matcher(message);
            boolean internalLiteral = false;
            while (literals.find()) {
                if (INTERNAL_TEXT.matcher(literals.group()).find()) {
                    internalLiteral = true;
                    break;
                }
            }
            if (internalLiteral || EXCEPTION_MESSAGE.matcher(message).find()) {
                int line = (int) source.substring(0, constructors.start()).lines().count();
                violations.add(path + ":" + line);
            }
        }
        return violations;
    }
}

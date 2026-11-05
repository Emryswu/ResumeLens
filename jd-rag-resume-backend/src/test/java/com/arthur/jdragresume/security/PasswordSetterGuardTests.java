package com.arthur.jdragresume.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordSetterGuardTests {
    @Test
    void productionPasswordHashWritesStayInsideRegistrationAndPasswordChange() throws Exception {
        Path root = Path.of("src/main/java");
        List<String> unauthorized = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                if (content.contains(".setPasswordHash(")
                        && !file.toString().endsWith("service/AuthService.java")
                        && !file.toString().endsWith("service/AppUserService.java")) {
                    unauthorized.add(root.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), unauthorized, "密码散列只能在注册和验证当前密码的修改路径写入");
    }
}

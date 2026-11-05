package com.arthur.jdragresume.collect;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BossPageBudgetConfigTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(BossPageBudgetConfig.class);

    @Test void theBudgetClockUsesShanghai() {
        context.run(app -> {
            assertEquals(List.of("clock"), List.of(app.getBeanNamesForType(Clock.class)));
            assertEquals(ZoneId.of("Asia/Shanghai"), app.getBean("clock", Clock.class).getZone());
        });
    }

    @Test void onlyOneClockBeanIsDeclaredAcrossProductionCode() throws Exception {
        Pattern definition = Pattern.compile(
                "@Bean\\b(?:\\s*\\([^)]*\\))?\\s*(?:(?:public|protected|private|static|final)\\s+)*Clock\\s+(\\w+)\\s*\\("
        );
        List<String> beanNames = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                Matcher matcher = definition.matcher(Files.readString(file));
                while (matcher.find()) {
                    beanNames.add(matcher.group(1));
                }
            }
        }
        assertEquals(List.of("clock"), beanNames.stream().sorted().toList(),
                "production should declare exactly one Clock bean named clock");
    }

    @Test void theRemovedZoneSettingCannotChangeTheSharedBudgetTimezone() {
        context.withPropertyValues("app.collect.budget-zone=UTC")
                .run(app -> assertEquals(ZoneId.of("Asia/Shanghai"), app.getBean(Clock.class).getZone()));
    }
}

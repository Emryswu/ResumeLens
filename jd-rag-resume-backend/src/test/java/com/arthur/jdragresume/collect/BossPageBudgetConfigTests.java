package com.arthur.jdragresume.collect;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BossPageBudgetConfigTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(BossPageBudgetConfig.class);

    @Test void theBudgetClockUsesShanghai() {
        context.run(app -> assertEquals(ZoneId.of("Asia/Shanghai"), app.getBean(Clock.class).getZone()));
    }

    @Test void theRemovedZoneSettingCannotChangeTheSharedBudgetTimezone() {
        context.withPropertyValues("app.collect.budget-zone=UTC")
                .run(app -> assertEquals(ZoneId.of("Asia/Shanghai"), app.getBean(Clock.class).getZone()));
    }
}

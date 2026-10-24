package com.arthur.jdragresume.collect;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BossPageBudgetConfig {
    @Bean
    public Clock bossPageBudgetClock(@Value("${app.collect.budget-zone:Asia/Shanghai}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}

package com.arthur.jdragresume.collect;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BossPageBudgetConfig {
    @Bean
    public Clock bossPageBudgetClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}

package com.arthur.jdragresume.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.rate-limit")
@Validated
public class RateLimitProperties {
    @Pattern(regexp = "(?i)memory|jdbc", message = "app.rate-limit.mode must be 'memory' or 'jdbc'")
    private String mode = "memory";

    @Min(1000)
    private long cleanupIntervalMs = 60_000L;

    @Min(10)
    private int cleanupBatchSize = 1000;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}

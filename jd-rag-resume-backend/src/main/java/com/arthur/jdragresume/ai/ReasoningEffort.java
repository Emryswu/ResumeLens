package com.arthur.jdragresume.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ReasoningEffort {
    @JsonProperty("default") DEFAULT,
    @JsonProperty("off") OFF,
    @JsonProperty("low") LOW,
    @JsonProperty("medium") MEDIUM,
    @JsonProperty("high") HIGH,
    @JsonProperty("xhigh") XHIGH,
    @JsonProperty("max") MAX;

    public String providerValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

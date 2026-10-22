package com.arthur.jdragresume.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ReasoningEffort {
    @JsonProperty("off") OFF,
    @JsonProperty("low") LOW,
    @JsonProperty("high") HIGH,
    @JsonProperty("max") MAX;

    public String providerValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

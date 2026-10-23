package com.arthur.jdragresume.collect;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A user preference from the chat UI; never a model tool argument. */
public enum CollectorMode {
    @JsonProperty("auto") AUTO,
    @JsonProperty("chrome") CHROME,
    @JsonProperty("patchright") PATCHRIGHT
}

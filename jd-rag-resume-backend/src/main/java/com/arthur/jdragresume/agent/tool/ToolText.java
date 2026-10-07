package com.arthur.jdragresume.agent.tool;

/** Text helpers shared by the tools and the loop's step previews. */
public final class ToolText {
    private ToolText() {
    }

    /** Strips, then caps at {@code maxChars} with an ellipsis; null stays null. */
    public static String clip(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "…";
    }

    static String keyword(com.fasterxml.jackson.databind.JsonNode arguments) {
        return arguments.path("keyword").asText("");
    }
}

package com.arthur.jdragresume.agent.tool;

final class ToolText {
    private ToolText() {
    }

    static String clip(String value, int maxChars) {
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

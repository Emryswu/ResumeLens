package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire format of a tool result as the model sees it. Payloads sit under {@code untrusted_data}
 * so the system prompt can draw a hard line: everything in there is data from resumes, job
 * posts or the database, never instructions, even when it is phrased like one.
 */
final class ToolEnvelope {
    private ToolEnvelope() {
    }

    /** {@code dataJson} is {@code data.toString()}, passed in because the caller needs it too. */
    static String ok(String tool, JsonNode data, String dataJson, int maxChars) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("tool", tool);
        root.put("ok", true);
        if (dataJson.length() > maxChars) {
            root.put("truncated", true);
            root.put("untrusted_data", dataJson.substring(0, maxChars));
        } else {
            root.set("untrusted_data", data);
        }
        return root.toString();
    }

    static String error(String tool, String code, String message) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("tool", tool);
        root.put("ok", false);
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return root.toString();
    }

    static JsonNode toTree(ObjectMapper objectMapper, Object data) {
        try {
            return objectMapper.valueToTree(data);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("tool result is not serializable", ex);
        }
    }
}

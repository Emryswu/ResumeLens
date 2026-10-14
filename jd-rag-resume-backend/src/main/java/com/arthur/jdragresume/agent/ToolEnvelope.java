package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
            JsonNode kept = data.deepCopy();
            int keptChars = dataJson.length();
            int omitted = 0;
            while (keptChars > maxChars) {
                ArrayNode items = removableItems(kept);
                // A single item or non-list result cannot be shortened without losing its meaning or shape.
                if (items == null) {
                    throw new BusinessException("TOOL_RESULT_TOO_LARGE", "工具结果超过长度上限，请缩小查询范围");
                }
                JsonNode removed = items.remove(items.size() - 1);
                keptChars -= removed.toString().length() + 1; // The removed item and its preceding comma.
                omitted++;
            }
            root.put("truncated", true);
            root.put("omittedItems", omitted);
            root.set("untrusted_data", kept);
        } else {
            root.set("untrusted_data", data);
        }
        return root.toString();
    }

    /** Current list tools return either an array or an object with a top-level items array. */
    private static ArrayNode removableItems(JsonNode data) {
        if (data.isArray() && data.size() > 1) {
            return (ArrayNode) data;
        }
        if (data.isObject()) {
            for (JsonNode value : data) {
                if (value.isArray() && value.size() > 1) {
                    return (ArrayNode) value;
                }
            }
        }
        return null;
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

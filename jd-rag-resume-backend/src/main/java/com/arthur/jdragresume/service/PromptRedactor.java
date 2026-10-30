package com.arthur.jdragresume.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Pattern;

/** Basic phone/email redaction for outbound copies only; other identifying details remain. */
public final class PromptRedactor {
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])(?:\\+?86[ -]?)?1[3-9][0-9][ -]?[0-9]{4}[ -]?[0-9]{4}(?![0-9])");
    // A dotted domain is required. Preserve scp-style SSH URLs, including git@github.com:org/repo.git.
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-])[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"
                    + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\\.[a-zA-Z]{2,}"
                    + "(?![a-zA-Z0-9-]|\\.[a-zA-Z0-9]|:[^\\s]+/)");

    private PromptRedactor() { }

    public static String redact(String text) {
        // Assistant content/reasoning can be null in tool-only turns.
        if (text == null) return null;
        return PHONE.matcher(EMAIL.matcher(text).replaceAll("[邮箱]")).replaceAll("[手机号]");
    }

    /** Copy every textual JSON value, including future tool payloads, without mutating stored context. */
    public static JsonNode redact(JsonNode value) {
        if (value.isTextual()) return JsonNodeFactory.instance.textNode(redact(value.textValue()));
        if (value.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            value.fields().forEachRemaining(field -> copy.set(field.getKey(), redact(field.getValue())));
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> copy.add(redact(item)));
            return copy;
        }
        return value;
    }
}

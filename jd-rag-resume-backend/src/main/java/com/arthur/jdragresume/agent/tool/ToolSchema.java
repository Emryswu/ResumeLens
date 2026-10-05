package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema subset the tools need (flat objects of integers and strings), plus a
 * validator for exactly that subset. The schema sent to the model and the one enforced
 * before execution are the same object, so they cannot drift apart.
 */
public final class ToolSchema {
    private final Map<String, Property> properties = new LinkedHashMap<>();

    public static ToolSchema object() {
        return new ToolSchema();
    }

    public ToolSchema integer(String name, String description, boolean required, long minimum, long maximum) {
        properties.put(name, new Property("integer", description, required, minimum, maximum, 0));
        return this;
    }

    public ToolSchema string(String name, String description, boolean required, int maxLength) {
        properties.put(name, new Property("string", description, required, 0, 0, maxLength));
        return this;
    }

    public JsonNode toJson() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode root = factory.objectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        List<String> required = new ArrayList<>();
        properties.forEach((name, property) -> {
            ObjectNode node = props.putObject(name);
            node.put("type", property.type());
            node.put("description", property.description());
            if (property.type().equals("integer")) {
                node.put("minimum", property.minimum());
                node.put("maximum", property.maximum());
            } else {
                node.put("maxLength", property.maxLength());
            }
            if (property.required()) {
                required.add(name);
            }
        });
        required.forEach(root.putArray("required")::add);
        root.put("additionalProperties", false);
        return root;
    }

    /** Returns problems in plain language for the model to read and fix; empty when valid. */
    public List<String> validate(JsonNode arguments) {
        List<String> problems = new ArrayList<>();
        if (arguments == null || !arguments.isObject()) {
            problems.add("arguments must be a JSON object");
            return problems;
        }
        Iterator<String> names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!properties.containsKey(name)) {
                problems.add("unknown argument '" + name + "', allowed: " + properties.keySet());
            }
        }
        properties.forEach((name, property) -> {
            JsonNode value = arguments.get(name);
            if (value == null || value.isNull()) {
                if (property.required()) {
                    problems.add("missing required argument '" + name + "'");
                }
                return;
            }
            if (property.type().equals("integer")) {
                if (!value.isIntegralNumber()) {
                    problems.add("'" + name + "' must be an integer");
                } else if (value.asLong() < property.minimum() || value.asLong() > property.maximum()) {
                    problems.add("'" + name + "' must be between " + property.minimum() + " and " + property.maximum());
                }
            } else if (!value.isTextual()) {
                problems.add("'" + name + "' must be a string");
            } else if (value.asText().length() > property.maxLength()) {
                problems.add("'" + name + "' must be at most " + property.maxLength() + " characters");
            }
        });
        return problems;
    }

    private record Property(String type, String description, boolean required, long minimum, long maximum, int maxLength) {
    }
}

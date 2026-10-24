package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Projects durable events into the same turn cards as the live SSE reducer. */
public final class ConversationTurns {
    private ConversationTurns() {}

    public static ArrayNode project(List<ConversationStore.Event> events) {
        ArrayNode turns = JsonNodeFactory.instance.arrayNode();
        ObjectNode turn = null;
        for (var event : events) {
            JsonNode data = event.data();
            if (event.type().equals("ask")) {
                turn = turns.addObject();
                turn.put("question", data.path("question").asText());
                turn.putArray("steps");
                turn.putArray("started");
                turn.putArray("unfinished");
                turn.putArray("notes");
                continue;
            }
            if (turn == null) throw new IllegalStateException("conversation event has no question");
            switch (event.type()) {
                case "decide" -> {
                    turn.remove("error");
                    if (data.path("approved").asBoolean()) turn.set("approvedCallId", data.get("toolCallId"));
                }
                case "browser_report" -> {
                    turn.remove("error");
                    turn.set("browserOutcome", data.get("outcome"));
                }
                case "step_started" -> {
                    boolean seen = false;
                    for (JsonNode started : turn.withArray("started")) {
                        if (started.path("toolCallId").equals(data.path("toolCallId"))) seen = true;
                    }
                    if (!seen) ((ArrayNode) turn.get("started")).add(data);
                }
                case "step" -> {
                    ((ArrayNode) turn.get("steps")).add(data);
                    if (turn.path("confirmation").path("toolCallId").equals(data.path("toolCallId"))) {
                        turn.put("decision", data.path("errorCode").asText().equals("USER_REJECTED") ? "rejected" : "approved");
                    }
                    if (data.path("tool").asText().equals("start_analysis") && data.path("ok").asBoolean()) {
                        // The tool's structured facts contain the same id as its preview; older events use the preview.
                        JsonNode id = data.path("facts").path("analysisId");
                        if (id.isNumber()) turn.set("analysisId", id);
                        else {
                            try {
                                JsonNode preview = ConversationStore.JSON.readTree(data.path("resultPreview").asText());
                                if (preview.path("analysisId").isNumber()) turn.set("analysisId", preview.get("analysisId"));
                            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* A clipped preview has no link. */ }
                        }
                    }
                }
                case "note" -> ((ArrayNode) turn.get("notes")).add(data.path("content").asText());
                case "collector_progress" -> {
                    ObjectNode progress = turn.has("collectorProgress") ? (ObjectNode) turn.get("collectorProgress") : turn.putObject("collectorProgress");
                    progress.set(data.path("toolCallId").asText(), data);
                }
                case "message" -> turn.put("answer", displayAnswer(data.path("content").asText()));
                case "error" -> turn.set("error", data);
                case "confirmation_required" -> {
                    turn.set("confirmation", data);
                    turn.remove("decision");
                }
                case "browser_task_required" -> {
                    turn.set("browserTask", data);
                    turn.remove(List.of("browserProgress", "browserOutcome"));
                }
                case "done" -> {
                    ArrayNode unfinished = turn.putArray("unfinished");
                    for (JsonNode started : turn.withArray("started")) {
                        boolean finished = false;
                        for (JsonNode step : turn.withArray("steps")) {
                            if (step.path("toolCallId").equals(started.path("toolCallId"))) finished = true;
                        }
                        if (!finished) unfinished.add(started.path("toolCallId"));
                    }
                    if (turn.has("browserOutcome")) {
                        JsonNode browser = turn.path("browserTask").path("toolCallId");
                        boolean recorded = false;
                        for (JsonNode step : turn.withArray("steps")) if (step.path("toolCallId").equals(browser)) recorded = true;
                        for (JsonNode call : unfinished) if (call.equals(browser)) recorded = true;
                        if (!recorded) unfinished.add(browser);
                    }
                }
                default -> { /* Plan traces are retained as events without adding a card. */ }
            }
        }
        return turns;
    }

    private static String displayAnswer(String content) {
        String fallback = "查询结果已保留，请在处理过程中查看；本轮未能生成完整回答。";
        if (content.contains("\"untrusted_data\"")) return fallback;
        try {
            JsonNode parsed = ConversationStore.JSON.readTree(content);
            if (parsed != null && parsed.isContainerNode()) return fallback;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* Normal prose. */ }
        return content;
    }
}

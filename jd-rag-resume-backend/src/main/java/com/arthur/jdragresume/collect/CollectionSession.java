package com.arthur.jdragresume.collect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.function.Consumer;

/** Server-owned identity, user choice and progress sink for one already budgeted tool call. */
public record CollectionSession(Long userId, String conversationId, String reservationId, String toolCallId,
                                CollectorMode mode, long deadline, Consumer<Progress> progress) {
    public void announce(Map<String, Object> command, JsonNode task, String switchReason) {
        var view = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (String key : java.util.List.of("phase", "pagesRead", "pageLog", "finishReason", "stopReason")) {
            if (task.has(key)) view.set(key, task.get(key));
        }
        view.put("jobCount", task.path("jobs").size());
        progress.accept(new Progress(toolCallId, command, view, switchReason));
    }

    public record Progress(String toolCallId, Map<String, Object> command, JsonNode progress, String switchReason) { }
}

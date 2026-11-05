package com.arthur.jdragresume.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds tool calls without results and the boundary where each missing result belongs. */
final class ToolCallPairs {
    private ToolCallPairs() {
    }

    static List<Unpaired> unpaired(List<AgentMessage> transcript) {
        Map<String, AgentMessage.ToolCall> open = new LinkedHashMap<>();
        List<Unpaired> unpaired = new ArrayList<>();
        for (int index = 0; index < transcript.size(); index++) {
            AgentMessage message = transcript.get(index);
            if (AgentMessage.TOOL.equals(message.role())) {
                if (open.remove(message.toolCallId()) == null) {
                    throw new IllegalStateException("tool result has no outstanding call");
                }
                continue;
            }
            if (!open.isEmpty()) {
                for (var call : open.values()) unpaired.add(new Unpaired(call, index));
                open.clear();
            }
            if (message.hasToolCalls()) {
                for (var call : message.toolCalls()) {
                    if (open.putIfAbsent(call.id(), call) != null) {
                        throw new IllegalStateException("duplicate tool call id in a batch");
                    }
                }
            }
        }
        for (var call : open.values()) unpaired.add(new Unpaired(call, transcript.size()));
        return List.copyOf(unpaired);
    }

    record Unpaired(AgentMessage.ToolCall call, int insertBefore) {
    }
}

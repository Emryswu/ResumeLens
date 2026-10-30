package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * What the current turn has spent of its budgets, rebuilt from the server-held transcript and
 * nothing else. A turn can pause for a confirmation or the browser and continue in a later request;
 * counting per request would hand every resume a fresh budget, so first and resumed requests alike
 * read the counts here.
 *
 * @param toolSteps            tool calls that took a step: every result except plan submissions and calls the
 *                             loop closed before stepping them ({@link #NOT_STEPPED})
 * @param modelCalls           model replies; each one the turn continued from is an assistant message
 * @param corrections          plan protocol corrections given in this turn
 * @param correctionsSincePlan corrections since the last accepted plan, which reset the model-call budget
 * @param planAccepted         whether this turn has accepted a plan
 */
record TurnUsage(int toolSteps, int modelCalls, int corrections, int correctionsSincePlan, boolean planAccepted) {
    /** Codes of results the loop wrote for calls it closed before counting a step for them. */
    static final Set<String> NOT_STEPPED = Set.of("AGENT_PLAN_INVALID", "AGENT_PLAN_MISMATCH", "AGENT_REPLAN_LIMIT",
            "AGENT_STEP_LIMIT", "AGENT_PLAN_STEP_SKIPPED");
    /** Codes of a batch closed so the model can correct its plan protocol and try again. */
    static final Set<String> CORRECTION = Set.of("AGENT_PLAN_INVALID", "AGENT_PLAN_MISMATCH");

    static TurnUsage of(List<AgentMessage> transcript, ObjectMapper mapper) {
        int toolSteps = 0;
        int modelCalls = 0;
        int corrections = 0;
        int correctionsSincePlan = 0;
        boolean planAccepted = false;
        List<AgentMessage.ToolCall> batch = List.of();
        boolean corrected = false;
        for (AgentMessage message : transcript.subList(turnStart(transcript), transcript.size())) {
            if (AgentMessage.ASSISTANT.equals(message.role())) {
                modelCalls++;
                batch = message.hasToolCalls() ? message.toolCalls() : List.of();
                corrected = false;
                continue;
            }
            if (!AgentMessage.TOOL.equals(message.role())) continue;
            AgentMessage.ToolCall call = batch.stream().filter(item -> item.id().equals(message.toolCallId()))
                    .findFirst().orElse(null);
            if (call == null) continue;
            JsonNode result = read(mapper, message.content());
            String code = result.path("error").path("code").asText();
            if (AgentPlan.TOOL.equals(call.name()) && result.path("planAccepted").asBoolean(false)) {
                planAccepted = true;
                correctionsSincePlan = 0;
            }
            if (CORRECTION.contains(code) && !corrected) {
                corrected = true;
                corrections++;
                correctionsSincePlan++;
            }
            if (!AgentPlan.TOOL.equals(call.name()) && !NOT_STEPPED.contains(code)) toolSteps++;
        }
        return new TurnUsage(toolSteps, modelCalls, corrections, correctionsSincePlan, planAccepted);
    }

    /** The current turn starts after the last user message; a resumed request never adds one. */
    static int turnStart(List<AgentMessage> transcript) {
        for (int index = transcript.size() - 1; index >= 0; index--) {
            if (AgentMessage.USER.equals(transcript.get(index).role())) return index + 1;
        }
        return 0;
    }

    private static JsonNode read(ObjectMapper mapper, String content) {
        try {
            JsonNode node = mapper.readTree(content);
            return node == null ? mapper.createObjectNode() : node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("invalid server tool envelope", ex);
        }
    }
}

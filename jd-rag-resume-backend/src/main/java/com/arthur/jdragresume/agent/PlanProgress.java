package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where the current turn stands in its accepted plan, rebuilt from the server-held transcript and
 * nothing else. A turn can pause for a confirmation or the browser and continue in a later request,
 * so the loop never keeps this in local variables: every request, first or resumed, reads it here.
 */
record PlanProgress(AgentPlan plan, int cursor, boolean needsReplan, int replans) {
    static final PlanProgress NONE = new PlanProgress(null, 0, false, 0);

    /** Results the loop wrote without treating the call as a plan step; they never move the plan. */
    static final Set<String> NOT_A_STEP = Set.of("AGENT_PLAN_STEP_SKIPPED", "ONE_PENDING_ACTION_AT_A_TIME",
            "ONE_CONFIRMATION_AT_A_TIME", "AGENT_STEP_LIMIT");

    /**
     * Replays the turn since the last user message: the last accepted {@code submit_plan} sets the
     * plan, each earlier accepted one is a replan, and every result of a batch that matched the plan
     * moves the cursor or, when it failed or came back empty, leaves the plan waiting for a replan.
     */
    static PlanProgress of(List<AgentMessage> transcript, ObjectMapper mapper, AgentToolRegistry registry) {
        int start = 0;
        for (int index = transcript.size() - 1; index >= 0; index--) {
            if (AgentMessage.USER.equals(transcript.get(index).role())) { start = index + 1; break; }
        }
        PlanProgress progress = NONE;
        List<AgentMessage.ToolCall> batch = List.of();
        boolean planBatch = false;
        for (AgentMessage message : transcript.subList(start, transcript.size())) {
            if (message.hasToolCalls()) {
                batch = message.toolCalls();
                planBatch = progress.allows(batch);
                continue;
            }
            if (!AgentMessage.TOOL.equals(message.role())) continue;
            AgentMessage.ToolCall call = batch.stream().filter(item -> item.id().equals(message.toolCallId()))
                    .findFirst().orElse(null);
            if (call == null) continue;
            JsonNode result = read(mapper, message.content());
            if (AgentPlan.TOOL.equals(call.name())) {
                if (result.path("planAccepted").asBoolean(false)) {
                    progress = new PlanProgress(AgentPlan.parse(call.arguments(), mapper, registry), 0, false,
                            progress.plan == null ? progress.replans : progress.replans + 1);
                }
            } else if (planBatch && progress.plan != null && !progress.needsReplan
                    && !NOT_A_STEP.contains(result.path("error").path("code").asText())) {
                boolean failed = !result.path("ok").asBoolean(false) || emptyData(result.path("untrusted_data"));
                progress = new PlanProgress(progress.plan, failed ? progress.cursor : progress.cursor + 1,
                        failed, progress.replans);
            }
        }
        return progress;
    }

    /** A batch runs as plan steps only when it is the next steps of a plan that is not waiting for a replan. */
    boolean allows(List<AgentMessage.ToolCall> calls) {
        if (plan == null || needsReplan || calls.isEmpty() || cursor + calls.size() > plan.steps().size()) return false;
        for (int index = 0; index < calls.size(); index++) {
            if (!calls.get(index).name().equals(plan.steps().get(cursor + index).tool())) return false;
        }
        return true;
    }

    boolean unfinished() {
        return plan != null && !needsReplan && cursor < plan.steps().size();
    }

    static boolean emptyData(JsonNode data) {
        if (data.isNull() || data.isMissingNode() || data.isArray() && data.isEmpty()
                || data.isTextual() && data.asText().isBlank() || data.isObject() && data.isEmpty()) return true;
        if (data.isObject()) {
            List<JsonNode> arrays = new ArrayList<>();
            data.forEach(value -> { if (value.isArray()) arrays.add(value); });
            return !arrays.isEmpty() && arrays.stream().allMatch(JsonNode::isEmpty);
        }
        return false;
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

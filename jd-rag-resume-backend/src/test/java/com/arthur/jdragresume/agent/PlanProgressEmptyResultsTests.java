package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.ListJobApplicationsTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlanProgressEmptyResultsTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anEmptyApplicationHistoryCompletesThePlannedQueryWithoutReplanning() {
        var registry = new AgentToolRegistry(List.of(new ListJobApplicationsTool(null)));
        var transcript = List.of(
                AgentMessage.user("看看我的投递记录"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("plan", AgentPlan.TOOL,
                        "{\"steps\":[{\"tool\":\"list_job_applications\",\"purpose\":\"查看投递记录\"}]}"))),
                AgentMessage.tool("plan", "{\"ok\":true,\"planAccepted\":true}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                        "applications", "list_job_applications", "{}"))),
                AgentMessage.tool("applications",
                        "{\"tool\":\"list_job_applications\",\"ok\":true,"
                                + "\"untrusted_data\":{\"applications\":[],\"returned\":0,\"limit\":50}}"));

        var progress = PlanProgress.of(transcript, mapper, registry);

        assertFalse(progress.needsReplan(), "no application history is a valid completed query");
        assertEquals(1, progress.cursor());
        assertFalse(progress.unfinished());
    }
}

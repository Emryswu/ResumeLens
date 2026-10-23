package com.arthur.jdragresume.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnsTests {
    @Test void anInterruptedBrowserReportIsUnfinishedAndRepeatedStartIdsAreNotDuplicated() {
        var events = List.of(
                event("ask", "{\"question\":\"采集\"}"),
                event("step_started", "{\"toolCallId\":\"read\",\"tool\":\"get_resume\"}"),
                event("step_started", "{\"toolCallId\":\"read\",\"tool\":\"get_resume\"}"),
                event("step", "{\"toolCallId\":\"read\",\"tool\":\"get_resume\",\"ok\":true}"),
                event("browser_task_required", "{\"taskId\":\"task\",\"toolCallId\":\"browser\"}"),
                event("done", "{\"state\":\"AWAITING_BROWSER\"}"),
                event("browser_report", "{\"outcome\":\"expired\"}"),
                event("error", "{\"code\":\"AGENT_INTERRUPTED\",\"message\":\"服务中断\"}"),
                event("done", "{\"state\":\"FAILED\"}"));
        var turn = ConversationTurns.project(events).get(0);
        assertEquals(1, turn.path("started").size());
        assertEquals("browser", turn.path("unfinished").get(0).asText());
        assertEquals("expired", turn.path("browserOutcome").asText());
        assertFalse(turn.has("synced"));
    }

    private ConversationStore.Event event(String type, String data) {
        return new ConversationStore.Event(1, "turn", "run", type, ConversationStore.read(data), "now");
    }
}

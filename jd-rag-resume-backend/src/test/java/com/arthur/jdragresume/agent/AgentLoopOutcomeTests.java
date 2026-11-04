package com.arthur.jdragresume.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopOutcomeTests {
    private final AgentMessage.ToolCall call = AgentMessage.ToolCall.of("c1", "get_job", "{}");

    private List<AgentMessage> pending() {
        return List.of(AgentMessage.user("查岗位"), AgentMessage.assistant(null, List.of(call)));
    }

    @ParameterizedTest
    @EnumSource(value = AgentLoop.State.class, names = {"AWAITING_CONFIRMATION", "AWAITING_BROWSER"}, mode = EnumSource.Mode.EXCLUDE)
    void terminalOutcomeRejectsUnansweredCalls(AgentLoop.State state) {
        assertThrows(IllegalStateException.class, () -> new AgentLoop.Outcome(state, pending(), 1));
    }

    @ParameterizedTest
    @EnumSource(AgentLoop.State.class)
    void noOutcomeMayHideAnUnansweredCallBeforeAnotherMessage(AgentLoop.State state) {
        for (AgentMessage next : List.of(AgentMessage.assistant("完成", null), AgentMessage.user("继续"))) {
            List<AgentMessage> broken = new ArrayList<>(pending());
            broken.add(next);
            assertThrows(IllegalStateException.class, () -> new AgentLoop.Outcome(state, broken, 1));
        }
    }

    @ParameterizedTest
    @EnumSource(value = AgentLoop.State.class, names = {"AWAITING_CONFIRMATION", "AWAITING_BROWSER"})
    void pausedOutcomeKeepsExactlyOnePendingCall(AgentLoop.State state) {
        assertDoesNotThrow(() -> new AgentLoop.Outcome(state, pending(), 1));
        assertThrows(IllegalStateException.class, () -> new AgentLoop.Outcome(state,
                List.of(AgentMessage.user("查岗位")), 1));
        assertThrows(IllegalStateException.class, () -> new AgentLoop.Outcome(state, List.of(
                AgentMessage.user("查岗位"), AgentMessage.assistant(null, List.of(call,
                        AgentMessage.ToolCall.of("c2", "get_job", "{}")))), 1));
    }

    @Test
    void completedToolRoundsCanReuseCallIds() {
        List<AgentMessage> messages = new ArrayList<>(pending());
        messages.add(AgentMessage.tool("c1", "{}"));
        messages.add(AgentMessage.assistant(null, List.of(call)));
        messages.add(AgentMessage.tool("c1", "{}"));
        messages.add(AgentMessage.assistant("完成", null));
        assertDoesNotThrow(() -> new AgentLoop.Outcome(AgentLoop.State.COMPLETED, messages, 2));
    }
}

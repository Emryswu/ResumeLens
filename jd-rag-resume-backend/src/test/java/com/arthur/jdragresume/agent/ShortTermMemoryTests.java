package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ShortTermMemoryTests {
    private final AgentProperties properties = new AgentProperties();

    @Test
    void summarizesOldWholeTurnsAndKeepsRecentRawTurnsAndPreviousSummary() {
        properties.setSummaryThresholdTurns(3);
        properties.setRecentTurns(2);
        List<AgentMessage> messages = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            messages.add(AgentMessage.user("第" + index + "轮问题"));
            messages.add(AgentMessage.assistant("第" + index + "轮回答", null));
        }
        messages.add(AgentMessage.user("继续"));
        var normalized = new TranscriptPolicy(properties).normalize(messages, null);
        assertEquals(messages.subList(6, 9), normalized.messages());
        assertEquals(messages.subList(0, 6), normalized.older());
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AgentLoop.DoneEvent> done = new AtomicReference<>();
        AgentModel model = (system, transcript, tools, timeout) -> {
            if (calls.incrementAndGet() == 1) {
                assertTrue(system.startsWith("SUMMARY_TASK:"));
                assertTrue(tools.isEmpty());
                assertTrue(transcript.getFirst().content().contains("上次摘要"));
                assertTrue(transcript.getFirst().content().contains("第0轮问题"));
                assertFalse(transcript.getFirst().content().contains("第3轮问题"));
                return AgentModel.Reply.answer("较早讨论了职位甲");
            }
            assertTrue(system.contains("较早讨论了职位甲"));
            assertEquals(normalized.messages(), transcript);
            return AgentModel.Reply.answer("继续准备");
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of()), new ObjectMapper(), properties, () -> 1000L);
        loop.run(normalized, null, new AgentToolContext(new AppUser()), (event, payload) -> {
            if ("done".equals(event)) done.set((AgentLoop.DoneEvent) payload);
        }, () -> false, 1000L, new AgentLoop.TurnOptions("上次摘要", List.of(), false));
        assertEquals("较早讨论了职位甲", done.get().summary());
        assertEquals(2, calls.get());
    }

    @Test
    void sizeEvictionsAreSummarizedInsteadOfSilentlyLost() {
        properties.setMaxMessages(3);
        var normalized = new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("旧问题"),
                AgentMessage.assistant("旧回答", null), AgentMessage.user("新问题"),
                AgentMessage.assistant("新回答", null), AgentMessage.user("继续")), null);
        assertEquals(List.of("旧问题", "旧回答"), normalized.older().stream().map(AgentMessage::content).toList());
    }

    @Test
    void recentToolResultsRemainVerbatimAndWholeToolRoundsArePreserved() {
        properties.setCompactedToolResultChars(5);
        String content = "原始结果".repeat(100);
        var messages = List.of(AgentMessage.user("查职位"), AgentMessage.assistant(null,
                List.of(AgentMessage.ToolCall.of("c", "lookup", "{}"))), AgentMessage.tool("c", content),
                AgentMessage.assistant("结果", null), AgentMessage.user("继续"));
        assertEquals(messages, new TranscriptPolicy(properties).normalize(messages, null).messages());
    }

    @Test
    void invalidSummariesFailExplicitly() {
        AgentModel model = (system, transcript, tools, timeout) -> AgentModel.Reply.answer("x".repeat(2001));
        var ex = assertThrows(BusinessException.class, () -> ShortTermMemory.summarize(model, new ObjectMapper(), properties,
                null, List.of(AgentMessage.user("旧问题")), Duration.ofSeconds(10)));
        assertEquals("AGENT_SUMMARY_INVALID", ex.getCode());
    }

    @Test
    void summaryFailureReturnsTheUncompressedHistoryForRetry() {
        properties.setSummaryThresholdTurns(1);
        properties.setRecentTurns(1);
        var original = List.of(AgentMessage.user("旧问题"), AgentMessage.assistant("旧回答", null), AgentMessage.user("新问题"));
        AgentModel broken = (system, transcript, tools, timeout) -> {
            throw new BusinessException("AI_TIMEOUT", "摘要超时");
        };
        var loop = new AgentLoop(broken, new AgentToolRegistry(List.of()), new ObjectMapper(), properties, () -> 1000L);
        var outcome = loop.run(new TranscriptPolicy(properties).normalize(original, null), null,
                new AgentToolContext(new AppUser()), (event, payload) -> { }, () -> false);
        assertEquals(AgentLoop.State.FAILED, outcome.state());
        assertEquals(original, outcome.transcript().subList(0, original.size()));
    }
}

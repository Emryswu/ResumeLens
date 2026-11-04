package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.ai.ReasoningEffort;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Meters are read off the two boundaries the loop already has (model and sink), so a real turn through
 * {@link AgentLoop} is the fixture: whatever the transcript records is what the counters show.
 */
class AgentMetricsTests {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final AgentToolContext context = new AgentToolContext(new AppUser());
    private final AgentLoopTests.LookupTool lookup = new AgentLoopTests.LookupTool();

    @Test
    void aCompletedTurnCountsModelCallsToolStepsAndItsTerminalState() {
        AgentLoopTests.ScriptedModel model = new AgentLoopTests.ScriptedModel(
                transcript -> AgentModel.Reply.call(call("c1", "delete_everything", "{}")),
                transcript -> AgentModel.Reply.call(call("c2", "lookup", "{\"id\":7}")),
                transcript -> AgentModel.Reply.answer("找到 7 号"));
        AgentLoopTests.RecordingSink delegate = new AgentLoopTests.RecordingSink();

        AgentLoop.Outcome outcome = loop(model).run(fresh("查一下 7 号"), null, context, metrics.sink(delegate), () -> false);

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1.0, counter(AgentMetrics.TURNS, "state", "COMPLETED").count());
        assertEquals(3, timer(AgentMetrics.MODEL_CALLS, "outcome", "ok", "effort", "high").count());
        assertEquals(1, timer(AgentMetrics.TOOL_CALLS, "tool", "lookup", "outcome", "ok").count());
        assertEquals(1, timer(AgentMetrics.TOOL_CALLS, "tool", "delete_everything", "outcome", "error").count());
        assertEquals(1.0, counter(AgentMetrics.TOOL_ERRORS, "tool", "delete_everything", "code", "UNKNOWN_TOOL").count());
        assertNull(registry.find(AgentMetrics.TURN_ERRORS).counter(), "a completed turn raises no turn-level error");
        // The metered sink is transparent: the durable sink still sees every event, in order, unchanged.
        assertEquals(List.of("step", "step_started", "step", "message", "done"), delegate.names());
    }

    @Test
    void aTurnEndedByTheBudgetCountsItsStateAndTheErrorTheUserSaw() {
        properties.setMaxSteps(2);
        AtomicInteger calls = new AtomicInteger();
        AgentModel model = (system, transcript, tools, timeout) -> AgentModel.Reply.call(
                call("c" + calls.incrementAndGet(), "lookup", "{\"id\":1}"));

        AgentLoop.Outcome outcome = loop(model).run(fresh("循环"), null, context,
                metrics.sink(new AgentLoopTests.RecordingSink()), () -> false);

        assertEquals(AgentLoop.State.STEP_LIMIT, outcome.state());
        assertEquals(1.0, counter(AgentMetrics.TURNS, "state", "STEP_LIMIT").count());
        assertEquals(1.0, counter(AgentMetrics.TURN_ERRORS, "code", "AGENT_STEP_LIMIT").count());
    }

    @Test
    void aFailingModelCallIsTimedAsAnErrorAndStillPropagates() {
        AgentModel failing = metrics.model((system, transcript, tools, timeout) -> {
            throw new BusinessException("AI_REQUEST_FAILED", "upstream 500");
        });

        assertThrows(BusinessException.class,
                () -> failing.next("system", List.of(), List.of(), Duration.ofSeconds(1), ReasoningEffort.LOW));

        assertEquals(1, timer(AgentMetrics.MODEL_CALLS, "outcome", "error", "effort", "low").count());
        assertNull(registry.find(AgentMetrics.MODEL_CALLS).tag("outcome", "ok").timer());
    }

    @Test
    void theFourArgumentModelCallIsTaggedWithTheDefaultEffortAndDelegatesReadiness() {
        AtomicInteger ready = new AtomicInteger();
        AgentModel metered = metrics.model(new AgentModel() {
            @Override public Reply next(String s, List<AgentMessage> t, List<ToolDefinition> tools, Duration timeout) {
                return Reply.answer("ok");
            }
            @Override public void requireReady() { ready.incrementAndGet(); }
        });

        metered.requireReady();
        assertEquals("ok", metered.next("system", List.of(), List.of(), Duration.ofSeconds(1)).content());

        assertEquals(1, ready.get());
        assertEquals(1, timer(AgentMetrics.MODEL_CALLS, "outcome", "ok", "effort", "default").count());
    }

    @Test
    void stepsAndErrorsWithUnexpectedPayloadShapesAreIgnoredRatherThanCounted() {
        metrics.observe("step", Map.of("tool", "lookup"));
        metrics.observe("done", Map.of("state", "COMPLETED"));
        metrics.observe("error", Map.of("message", "no code"));
        metrics.observe("note", Map.of("content", "x"));

        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    void noRegistryKeepsMetersInMemory() {
        AgentMetrics none = AgentMetrics.none();
        none.observe("done", new AgentLoop.DoneEvent(AgentLoop.State.FAILED, 0, List.of(), null, null, List.of()));
        assertEquals(1.0, none.registry().get(AgentMetrics.TURNS).tag("state", "FAILED").counter().count());
    }

    private Counter counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter();
    }

    private Timer timer(String name, String... tags) {
        return registry.get(name).tags(tags).timer();
    }

    private AgentLoop loop(AgentModel model) {
        AtomicLong clock = new AtomicLong(1_000_000);
        return new AgentLoop(metrics.model(model), new AgentToolRegistry(List.of(lookup)), objectMapper, properties, clock::get);
    }

    private TranscriptPolicy.Normalized fresh(String question) {
        return new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user(question)), null);
    }

    private static AgentMessage.ToolCall call(String id, String name, String arguments) {
        return AgentMessage.ToolCall.of(id, name, arguments);
    }
}

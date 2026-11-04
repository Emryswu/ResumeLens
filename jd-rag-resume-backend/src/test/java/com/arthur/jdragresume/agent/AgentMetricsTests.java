package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.ai.ReasoningEffort;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final AgentToolContext context = new AgentToolContext(new AppUser());
    private final AgentLoopTests.LookupTool lookup = new AgentLoopTests.LookupTool();
    private final AgentToolRegistry tools = new AgentToolRegistry(List.of(lookup));
    private final AgentMetrics metrics = new AgentMetrics(registry, tools.names());

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
        // The model asked for a tool that does not exist: the step is counted, but under the fixed unknown tag.
        assertEquals(1, timer(AgentMetrics.TOOL_CALLS, "tool", AgentMetrics.UNKNOWN_TOOL, "outcome", "error").count());
        assertEquals(1.0, counter(AgentMetrics.TOOL_ERRORS, "tool", AgentMetrics.UNKNOWN_TOOL, "code", "UNKNOWN_TOOL").count());
        assertNull(registry.find(AgentMetrics.TURN_ERRORS).counter(), "a completed turn raises no turn-level error");
        // The metered sink is transparent: the durable sink still sees every event, in order, unchanged.
        assertEquals(List.of("step", "step_started", "step", "message", "done"), delegate.names());
    }

    /**
     * The tool tag must stay a bounded set however many names the model invents: each distinct value is a
     * new time series in the exporter, and the name itself is model output.
     */
    @Test
    void toolTagValuesNeverLeaveTheRegistryNamesPlusUnknown() {
        for (String invented : List.of("delete_everything", "drop_table", "rm -rf /", "", "lookup2")) {
            metrics.observe("step", new AgentLoop.StepEvent("c", invented, "{}", false, "UNKNOWN_TOOL", 1, 0, "", null));
        }
        metrics.observe("step", new AgentLoop.StepEvent("c", null, "{}", false, "UNKNOWN_TOOL", 1, 0, "", null));
        metrics.observe("step", new AgentLoop.StepEvent("c", "lookup", "{}", true, null, 1, 0, "", null));

        Set<String> allowed = new HashSet<>(tools.names());
        allowed.add(AgentMetrics.UNKNOWN_TOOL);
        Set<String> seen = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            String tool = meter.getId().getTag("tool");
            if (tool != null) seen.add(tool);
        }
        assertEquals(Set.of("lookup", AgentMetrics.UNKNOWN_TOOL), seen);
        assertTrue(allowed.containsAll(seen));
        assertEquals(6.0, counter(AgentMetrics.TOOL_ERRORS, "tool", AgentMetrics.UNKNOWN_TOOL, "code", "UNKNOWN_TOOL").count());
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
        AgentMetrics none = AgentMetrics.none(tools);
        none.observe("done", new AgentLoop.DoneEvent(AgentLoop.State.FAILED, 0, List.of(), null, null, List.of()));
        assertEquals(1.0, none.registry().get(AgentMetrics.TURNS).tag("state", "FAILED").counter().count());
        assertEquals("lookup", none.toolTag("lookup"));
        assertEquals(AgentMetrics.UNKNOWN_TOOL, none.toolTag("delete_everything"));
    }

    private Counter counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter();
    }

    private Timer timer(String name, String... tags) {
        return registry.get(name).tags(tags).timer();
    }

    private AgentLoop loop(AgentModel model) {
        AtomicLong clock = new AtomicLong(1_000_000);
        return new AgentLoop(metrics.model(model), tools, objectMapper, properties, clock::get);
    }

    private TranscriptPolicy.Normalized fresh(String question) {
        return new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user(question)), null);
    }

    private static AgentMessage.ToolCall call(String id, String name, String arguments) {
        return AgentMessage.ToolCall.of(id, name, arguments);
    }
}

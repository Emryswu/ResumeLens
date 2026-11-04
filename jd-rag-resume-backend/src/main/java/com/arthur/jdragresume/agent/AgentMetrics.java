package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.ai.ReasoningEffort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Operational meters for the assistant. They are derived at two boundaries the loop already has, the model
 * interface and the event sink, so {@link AgentLoop} itself carries no instrumentation and the counters cannot
 * drift from what the transcript records. Tags are bounded: a tool tag is one of the registered names or
 * {@link #UNKNOWN_TOOL} (the model can ask for any name it likes, and a step is recorded for the refusal), error
 * codes are constants, states are the {@link AgentLoop.State} enum. No tag carries user data.
 */
public class AgentMetrics {
    /** Tool tag for a step whose name is not in the registry; the step's error code already says UNKNOWN_TOOL. */
    public static final String UNKNOWN_TOOL = "unknown";
    /** Turns by terminal state. */
    public static final String TURNS = "agent.turns";
    /** Model round trips by outcome and reasoning effort. */
    public static final String MODEL_CALLS = "agent.model.calls";
    /** Tool steps by tool and outcome; a refusal before execution counts as an error step like any other. */
    public static final String TOOL_CALLS = "agent.tool.calls";
    /** Failed tool steps by tool and error code. */
    public static final String TOOL_ERRORS = "agent.tool.errors";
    /** Turn-level errors the user saw, by code. */
    public static final String TURN_ERRORS = "agent.turn.errors";

    private final MeterRegistry registry;
    private final Set<String> tools;

    /** {@code tools} is the registry's name list: the only tool tag values besides {@link #UNKNOWN_TOOL}. */
    public AgentMetrics(MeterRegistry registry, Collection<String> tools) {
        this.registry = Objects.requireNonNull(registry);
        this.tools = Set.copyOf(tools);
    }

    /**
     * For code paths and tests that have no meter registry: meters are kept in memory and never exported.
     * A test double may pass no tool registry at all; every tool is then {@link #UNKNOWN_TOOL}.
     */
    public static AgentMetrics none(AgentToolRegistry tools) {
        return new AgentMetrics(new SimpleMeterRegistry(), tools == null ? List.of() : tools.names());
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** The only place a tool name becomes a tag value. */
    String toolTag(String name) {
        return name != null && tools.contains(name) ? name : UNKNOWN_TOOL;
    }

    public AgentModel model(AgentModel delegate) {
        return new AgentModel() {
            @Override
            public Reply next(String systemPrompt, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout) {
                return timed(null, () -> delegate.next(systemPrompt, transcript, tools, timeout));
            }

            @Override
            public Reply next(String systemPrompt, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout,
                              ReasoningEffort effort) {
                return timed(effort, () -> delegate.next(systemPrompt, transcript, tools, timeout, effort));
            }

            @Override
            public void requireReady() {
                delegate.requireReady();
            }
        };
    }

    private AgentModel.Reply timed(ReasoningEffort effort, java.util.function.Supplier<AgentModel.Reply> call) {
        String effortTag = effort == null ? "default" : effort.name().toLowerCase(Locale.ROOT);
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return call.get();
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            registry.timer(MODEL_CALLS, "outcome", outcome, "effort", effortTag)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** Observes the stream and forwards every event unchanged; the delegate still decides what is persisted. */
    public AgentEventSink sink(AgentEventSink delegate) {
        return new AgentEventSink() {
            @Override
            public void checkpoint(List<AgentMessage> messages, String summary) {
                delegate.checkpoint(messages, summary);
            }

            @Override
            public void emit(String event, Object payload) {
                observe(event, payload);
                delegate.emit(event, payload);
            }
        };
    }

    void observe(String event, Object payload) {
        switch (event) {
            case "step" -> {
                if (payload instanceof AgentLoop.StepEvent step) {
                    String tool = toolTag(step.tool());
                    String outcome = step.ok() ? "ok" : "error";
                    registry.timer(TOOL_CALLS, "tool", tool, "outcome", outcome)
                            .record(step.latencyMs(), TimeUnit.MILLISECONDS);
                    if (!step.ok()) {
                        registry.counter(TOOL_ERRORS, "tool", tool,
                                "code", Objects.requireNonNullElse(step.errorCode(), "UNKNOWN")).increment();
                    }
                }
            }
            case "done" -> {
                if (payload instanceof AgentLoop.DoneEvent done) {
                    registry.counter(TURNS, "state", done.state().name()).increment();
                }
            }
            case "error" -> {
                if (payload instanceof Map<?, ?> map && map.get("code") instanceof String code) {
                    registry.counter(TURN_ERRORS, "code", code).increment();
                }
            }
            default -> { }
        }
    }
}

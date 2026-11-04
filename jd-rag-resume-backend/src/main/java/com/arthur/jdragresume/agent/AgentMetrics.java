package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.ai.ReasoningEffort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Operational meters for the assistant. They are derived at two boundaries the loop already has, the model
 * interface and the event sink, so {@link AgentLoop} itself carries no instrumentation and the counters cannot
 * drift from what the transcript records. Tags are bounded: tool names come from the registry, error codes are
 * constants, states are the {@link AgentLoop.State} enum. No tag carries user data.
 */
public class AgentMetrics {
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

    public AgentMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /** For code paths and tests that have no registry: meters are kept in memory and never exported. */
    public static AgentMetrics none() {
        return new AgentMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
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
                    String outcome = step.ok() ? "ok" : "error";
                    registry.timer(TOOL_CALLS, "tool", step.tool(), "outcome", outcome)
                            .record(step.latencyMs(), TimeUnit.MILLISECONDS);
                    if (!step.ok()) {
                        registry.counter(TOOL_ERRORS, "tool", step.tool(),
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

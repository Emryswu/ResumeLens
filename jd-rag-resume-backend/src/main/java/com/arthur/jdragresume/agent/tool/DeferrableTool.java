package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A tool whose work may have to happen outside this server. {@link #begin} either finishes on
 * the spot or returns a command for the user's browser; the loop then ends the turn as
 * AWAITING_BROWSER, and a later request carrying the browser's report calls {@link #resume}
 * with the arguments the server recorded when it handed the command out.
 */
public interface DeferrableTool extends AgentTool {
    Begin begin(JsonNode arguments, AgentToolContext context);

    /** {@code report} arrived through the user's browser: untrusted, validated by the tool. */
    Object resume(JsonNode arguments, JsonNode report, AgentToolContext context);

    /** The loop never calls this for a deferrable tool; it goes through {@link #begin} instead. */
    @Override
    default Object execute(JsonNode arguments, AgentToolContext context) {
        throw new IllegalStateException(name() + " runs through begin/resume");
    }

    sealed interface Begin permits Done, InBrowser {
    }

    /** The work finished here; {@code result} becomes the tool result. */
    record Done(Object result) implements Begin {
    }

    /** The page must hand {@code action} with {@code command} to {@code executor} and report back. */
    record InBrowser(String executor, String action, Map<String, Object> command) implements Begin {
    }
}

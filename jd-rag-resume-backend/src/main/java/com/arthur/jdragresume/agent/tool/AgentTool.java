package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A capability the model may request. Implementations are thin adapters over existing
 * services, which already scope every query to the current user; the tool layer adds no
 * authorization of its own and must not bypass those services.
 */
public interface AgentTool {
    String name();

    /** Read by the model to decide when to call the tool; say what it returns and when not to use it. */
    String description();

    ToolSchema parameters();

    /** Serialized result ceiling; a bounded text tool can opt into enough room for its documented text limit. */
    default int resultMaxChars(int configuredLimit) {
        return configuredLimit;
    }

    /** Write tools pause the loop until the user approves the exact call in the UI. */
    default boolean requiresConfirmation() {
        return false;
    }

    /** Called only with arguments that already passed {@link ToolSchema#validate}. */
    Object execute(JsonNode arguments, AgentToolContext context);

    /**
     * What the user is asked to approve. Resolving it also proves the referenced records
     * exist and belong to the user, so a call with bad ids is bounced back to the model
     * instead of reaching the confirmation card.
     */
    default Object preview(JsonNode arguments, AgentToolContext context) {
        return null;
    }

    /**
     * A few fields of a successful result for the step bar, such as counts and how the call ended. The preview is
     * clipped text, so the page should not have to parse it. Null shows only the size and the preview.
     */
    default Object stepFacts(Object result) {
        return null;
    }
}

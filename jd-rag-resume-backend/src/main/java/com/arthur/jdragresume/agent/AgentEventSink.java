package com.arthur.jdragresume.agent;

/**
 * Where the loop reports progress. Implementations throw {@link ClientGoneException} when the
 * client has disconnected, which stops the loop before it spends another model call.
 */
public interface AgentEventSink {
    void emit(String event, Object payload);

    /** Full server context, checkpointed independently of previews and the final done event. */
    default void checkpoint(java.util.List<AgentMessage> messages, String summary) {}

    class ClientGoneException extends RuntimeException {
        public ClientGoneException(Throwable cause) {
            super("agent client disconnected", cause);
        }
    }
}

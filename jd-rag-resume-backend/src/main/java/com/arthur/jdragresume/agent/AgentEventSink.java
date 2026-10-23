package com.arthur.jdragresume.agent;

/**
 * Where the loop checkpoints context and reports progress. A disconnected SSE subscriber
 * must not stop persistence. The durable sink rejects an obsolete run with {@link ClientGoneException}.
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

package com.arthur.jdragresume.agent;

/**
 * Where the loop reports progress. Implementations throw {@link ClientGoneException} when the
 * client has disconnected, which stops the loop before it spends another model call.
 */
public interface AgentEventSink {
    void emit(String event, Object payload);

    class ClientGoneException extends RuntimeException {
        public ClientGoneException(Throwable cause) {
            super("agent client disconnected", cause);
        }
    }
}

package ai.javaclaw.agent;

import java.util.function.Consumer;

public interface Agent {

    /** Answers a request and returns the whole reply. */
    String respondTo(AgentRequest request);

    /**
     * Answers a request, reporting progress as {@link AgentEvent}s. Where the events go —
     * WebSocket, SSE, console — is entirely the caller's concern.
     */
    void respondTo(AgentRequest request, Consumer<AgentEvent> events);

    /** Answers a request as an instance of {@code result}. */
    <T> T prompt(AgentRequest request, Class<T> result);
}

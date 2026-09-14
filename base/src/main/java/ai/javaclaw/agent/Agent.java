package ai.javaclaw.agent;

import java.util.function.Consumer;

public interface Agent {

    String respondTo(String conversationId, String question);

    /**
     * Answers, reporting progress as {@link AgentEvent}s. Where the events go — WebSocket, SSE,
     * console — is entirely the caller's concern.
     */
    default void respondTo(String conversationId, String question, Consumer<AgentEvent> events) {
        events.accept(new AgentEvent.Token(respondTo(conversationId, question)));
        events.accept(new AgentEvent.Done());
    }

    <T> T prompt(String conversationId, String input, Class<T> result);

}

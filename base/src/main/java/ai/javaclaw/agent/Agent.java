package ai.javaclaw.agent;

import ai.javaclaw.approval.HumanQuestionHandler;

import java.util.function.Consumer;

public interface Agent {

    String respondTo(String conversationId, String question);

    /**
     * Answers, reporting progress as {@link AgentEvent}s. Where the events go — WebSocket, SSE,
     * console — is entirely the caller's concern. Nobody can approve tool calls during this turn.
     */
    default void respondTo(String conversationId, String question, Consumer<AgentEvent> events) {
        events.accept(new AgentEvent.Token(respondTo(conversationId, question)));
        events.accept(new AgentEvent.Done());
    }

    /**
     * Like {@link #respondTo(String, String, Consumer)}, for a caller that can put approval requests
     * in front of the owner: they arrive as {@link AgentEvent.ApprovalRequested} and are asked only
     * while {@code questions} says someone can answer.
     */
    default void respondTo(String conversationId, String question, Consumer<AgentEvent> events,
                           HumanQuestionHandler questions) {
        respondTo(conversationId, question, events);
    }

    /**
     * Answers in one piece, like {@link #respondTo(String, String)}, for a caller that can put
     * approval requests in front of the owner (a messaging channel): they arrive as
     * {@link AgentEvent.ApprovalRequested} on {@code events}, and are asked only while
     * {@code questions} says someone can answer.
     */
    default String answer(String conversationId, String question, HumanQuestionHandler questions,
                          Consumer<AgentEvent> events) {
        return respondTo(conversationId, question);
    }

    <T> T prompt(String conversationId, String input, Class<T> result);

    /** Like {@link #prompt(String, String, Class)}, also reporting tool and approval events. */
    default <T> T prompt(String conversationId, String input, Class<T> result, Consumer<AgentEvent> events) {
        return prompt(conversationId, input, result);
    }

}

package ai.javaclaw.agent;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.HumanQuestionHandler;

import java.util.List;
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
     * in front of the owner. When a tool call needs approval and {@code questions} says someone can
     * answer, the turn pauses: the requests arrive as {@link AgentEvent.ApprovalRequested} and the
     * turn ends with {@link AgentEvent.Done}. {@link #decide} resumes it.
     */
    default void respondTo(String conversationId, String question, Consumer<AgentEvent> events,
                           HumanQuestionHandler questions) {
        respondTo(conversationId, question, events);
    }

    /**
     * Answers in one piece, like {@link #respondTo(String, String)}, for a caller that can put
     * approval requests in front of the owner (a messaging channel). A paused turn returns the text
     * before the pause, which may be blank; see {@link #respondTo(String, String, Consumer, HumanQuestionHandler)}.
     */
    default String answer(String conversationId, String question, HumanQuestionHandler questions,
                          Consumer<AgentEvent> events) {
        return respondTo(conversationId, question);
    }

    /**
     * Applies the owner's decision on a request of the conversation's paused reply. When it was the
     * last open request, the reply's tool calls run as decided and the turn continues, streamed to
     * {@code events} like a turn of its own (and may pause again). A decision that matches no waiting
     * request of this conversation changes nothing.
     */
    default DecisionResult decide(String conversationId, String approvalId, String argumentsHash,
                                  ApprovalDecision decision, HumanQuestionHandler questions,
                                  Consumer<AgentEvent> events) {
        return DecisionResult.IGNORED;
    }

    /** The requests the conversation's paused reply still waits on. */
    default List<ApprovalRequest> waitingForApproval(String conversationId) {
        return List.of();
    }

    <T> T prompt(String conversationId, String input, Class<T> result);

    /** Like {@link #prompt(String, String, Class)}, also reporting tool and approval events. */
    default <T> T prompt(String conversationId, String input, Class<T> result, Consumer<AgentEvent> events) {
        return prompt(conversationId, input, result);
    }

    /** What a decision did. */
    enum DecisionResult {
        /** No waiting request matched: unknown, already answered, or another conversation's. */
        IGNORED,
        /** Recorded; other requests of the same reply still wait. */
        RECORDED,
        /** Recorded, and the turn continued: {@code events} got the rest of it, ending in Done or Failed. */
        RESUMED
    }
}

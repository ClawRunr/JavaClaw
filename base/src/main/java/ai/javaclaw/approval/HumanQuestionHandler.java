package ai.javaclaw.approval;

/**
 * Whoever can answer approval requests for a turn: the web chat, or the messaging chat the turn
 * came from. A turn with no handler (scheduled tasks) cannot ask, so its calls that need approval
 * are refused. A handler that throws is treated as unable to ask and the call is refused, never run.
 * <p>
 * The question itself reaches the owner through the turn's {@link ApprovalListener}; the answer
 * comes back through {@link PendingApprovals#decide}. Named after the equivalent interface in
 * spring-ai-playground (Apache 2.0), which inspired this design.
 * </p>
 */
@FunctionalInterface
public interface HumanQuestionHandler {

    /** Whether someone is connected right now who will see and can answer a request in this conversation. */
    boolean canAsk(String conversationId);
}

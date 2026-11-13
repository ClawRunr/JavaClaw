package ai.javaclaw.approval;

/**
 * Whoever can answer approval requests for a turn: the web chat, or the messaging chat the turn
 * came from. A turn with no handler (scheduled tasks), or whose handler says no or throws, cannot
 * ask: the calls that need approval are denied right away and the turn carries on.
 * <p>
 * Named after the equivalent interface in spring-ai-playground (Apache 2.0), which inspired this
 * design.
 * </p>
 */
@FunctionalInterface
public interface HumanQuestionHandler {

    /** Whether someone is connected right now who will see and can answer a request in this conversation. */
    boolean canAsk(String conversationId);
}

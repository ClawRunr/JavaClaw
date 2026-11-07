package ai.javaclaw.approval;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approval state for one agent turn (one user message or one task run, across every round of the
 * tool loop). It rides to the gate in the request's tool context under {@link #TOOL_CONTEXT_KEY};
 * the gate removes it again before any tool sees the context.
 * <p>
 * Remembers which tools were denied, so a tool denied once is refused for the rest of the turn
 * without asking again.
 * </p>
 */
public final class ApprovalTurn {

    public static final String TOOL_CONTEXT_KEY = ApprovalTurn.class.getName();

    private final String conversationId;
    private final HumanQuestionHandler questions;
    private final ApprovalListener listener;
    private final Map<String, Denial> deniedTools = new ConcurrentHashMap<>();

    /**
     * @param questions who can answer approvals for this turn, or {@code null} when nobody can
     * @param listener  receives requests and outcomes; may be {@link ApprovalListener#NONE}
     */
    public ApprovalTurn(String conversationId, HumanQuestionHandler questions, ApprovalListener listener) {
        this.conversationId = conversationId;
        this.questions = questions;
        this.listener = listener == null ? ApprovalListener.NONE : listener;
    }

    /** Reads the turn a request carries, if any. */
    public static Optional<ApprovalTurn> from(Map<String, Object> toolContext) {
        return toolContext != null && toolContext.get(TOOL_CONTEXT_KEY) instanceof ApprovalTurn turn
                ? Optional.of(turn)
                : Optional.empty();
    }

    /** The entry to put in a request's tool context. */
    public Map<String, Object> toolContext() {
        return Map.of(TOOL_CONTEXT_KEY, this);
    }

    public String conversationId() {
        return conversationId;
    }

    /** Empty when nobody can be asked during this turn. */
    public Optional<HumanQuestionHandler> questions() {
        return Optional.ofNullable(questions);
    }

    public ApprovalListener listener() {
        return listener;
    }

    Optional<Denial> earlierDenial(String toolName) {
        return Optional.ofNullable(deniedTools.get(toolName));
    }

    void recordDenial(String toolName, Denial denial) {
        deniedTools.putIfAbsent(toolName, denial);
    }

    /** Kept short: request logging prints the tool context. */
    @Override
    public String toString() {
        return "ApprovalTurn[" + conversationId + "]";
    }
}

package ai.javaclaw.approval;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The paused reply of each conversation, in memory: at most one per conversation, gone on restart.
 * Taking a paused turn out is atomic, so a pause is resumed, or moved on from, exactly once.
 */
public class PausedTurns {

    private final Map<String, PausedTurn> paused = new ConcurrentHashMap<>();

    /** Keeps a paused turn; its conversation must not be paused already. */
    public void pause(PausedTurn turn) {
        if (paused.putIfAbsent(turn.conversationId(), turn) != null) {
            throw new IllegalStateException("Conversation " + turn.conversationId() + " is already paused");
        }
    }

    public Optional<PausedTurn> get(String conversationId) {
        return Optional.ofNullable(conversationId == null ? null : paused.get(conversationId));
    }

    /**
     * Takes out exactly this paused turn.
     *
     * @return {@code false} when the conversation is no longer paused on it
     */
    public boolean remove(PausedTurn turn) {
        return paused.remove(turn.conversationId(), turn);
    }

    /** Takes out whatever the conversation is paused on. */
    public Optional<PausedTurn> take(String conversationId) {
        return Optional.ofNullable(paused.remove(conversationId));
    }

    /** The requests still waiting in a conversation, so a reconnecting client can show them again. */
    public List<ApprovalRequest> waitingIn(String conversationId) {
        return get(conversationId).map(PausedTurn::waiting).orElse(List.of());
    }
}

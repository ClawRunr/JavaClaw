package ai.javaclaw.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approval requests that are waiting for the owner, in memory. Each one is answered at most once:
 * a decision only lands when its id, conversation and argument hash all match a request that is
 * still pending, and landing removes it — so a wrong id, a changed hash or a replayed decision
 * does nothing.
 */
public class PendingApprovals {

    private static final Logger log = LoggerFactory.getLogger(PendingApprovals.class);

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** How a pending request ended. */
    public sealed interface Resolution {
        record Decided(ApprovalDecision decision) implements Resolution {}
        record Cancelled(Denial denial) implements Resolution {}
    }

    /** A request and the answer it is waiting for. */
    public record Pending(ApprovalRequest request, CompletableFuture<Resolution> answer) {}

    /** Starts waiting for an answer to {@code request}; its id must not already be pending. */
    public Pending open(ApprovalRequest request) {
        Pending entry = new Pending(request, new CompletableFuture<>());
        if (pending.putIfAbsent(request.approvalId(), entry) != null) {
            throw new IllegalStateException("Approval " + request.approvalId() + " is already pending");
        }
        return entry;
    }

    public boolean isPending(String approvalId) {
        return approvalId != null && pending.containsKey(approvalId);
    }

    /**
     * Answers a pending request.
     *
     * @return whether the decision was accepted; {@code false} leaves everything unchanged
     */
    public boolean decide(String conversationId, String approvalId, String argumentsHash, ApprovalDecision decision) {
        if (approvalId == null || decision == null) {
            return false;
        }
        Pending entry = pending.get(approvalId);
        if (entry == null) {
            log.info("Ignoring decision for unknown or already answered approval {}", approvalId);
            return false;
        }
        ApprovalRequest request = entry.request();
        if (!request.conversationId().equals(conversationId)) {
            log.warn("Ignoring decision for approval {} sent from another conversation", approvalId);
            return false;
        }
        if (!request.argumentsHash().equals(argumentsHash)) {
            log.warn("Ignoring decision for approval {}: arguments hash does not match", approvalId);
            return false;
        }
        if (!request.decisions().contains(decision)) {
            log.warn("Ignoring decision {} for approval {}: not offered", decision, approvalId);
            return false;
        }
        // remove(key, value) is the single-use guarantee: only one caller can win it
        return pending.remove(approvalId, entry)
                && entry.answer().complete(new Resolution.Decided(decision));
    }

    /** Denies every request still waiting in a conversation, e.g. when its last client leaves. */
    public int cancelConversation(String conversationId, Denial denial) {
        int cancelled = 0;
        for (Pending entry : List.copyOf(pending.values())) {
            if (entry.request().conversationId().equals(conversationId)
                    && pending.remove(entry.request().approvalId(), entry)
                    && entry.answer().complete(new Resolution.Cancelled(denial))) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Requests still waiting in a conversation, oldest first, so a reconnecting client can show them again. */
    public List<ApprovalRequest> pendingFor(String conversationId) {
        return pending.values().stream()
                .map(Pending::request)
                .filter(request -> request.conversationId().equals(conversationId))
                .sorted((a, b) -> a.expiresAt().compareTo(b.expiresAt()))
                .toList();
    }

    /** Forgets a request the gate stopped waiting for (timed out or interrupted). */
    void discard(Pending entry) {
        pending.remove(entry.request().approvalId(), entry);
    }
}

package ai.javaclaw.approval;

import java.util.Set;

/**
 * Applies {@code agent.approval.allow} and {@code agent.approval.deny} to one call. Order: deny,
 * then protected path, then allow; everything else asks.
 */
public class ApprovalPolicy {

    public enum Verdict { RUN, ASK, DENY }

    private final Set<String> allow;
    private final Set<String> deny;

    public ApprovalPolicy(ApprovalProperties properties) {
        this.allow = Set.copyOf(properties.allow());
        this.deny = Set.copyOf(properties.deny());
    }

    public Verdict evaluate(String toolName, boolean touchesProtectedPath) {
        if (deny.contains(toolName)) {
            return Verdict.DENY;
        }
        // Above allow, so no allowed tool can change what keeps the gate in place
        if (touchesProtectedPath) {
            return Verdict.ASK;
        }
        return allow.contains(toolName) ? Verdict.RUN : Verdict.ASK;
    }

    /** Denied tools are never offered to the model. */
    public boolean isOffered(String toolName) {
        return !deny.contains(toolName);
    }
}

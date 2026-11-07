package ai.javaclaw.approval;

import java.util.Optional;
import java.util.Set;

/**
 * Applies {@code agent.approval.rules} to one call. Order: deny, then protected actions, then ask,
 * then allow — first match wins. With no match a {@link ToolRisk#READ_ONLY} tool runs and anything
 * else, including an unclassified tool, asks.
 */
public class ApprovalPolicy {

    public enum Verdict { RUN, ASK, DENY }

    private final Set<String> allow;
    private final Set<String> ask;
    private final Set<String> deny;

    public ApprovalPolicy(ApprovalProperties.Rules rules) {
        this.allow = Set.copyOf(rules.allow());
        this.ask = Set.copyOf(rules.ask());
        this.deny = Set.copyOf(rules.deny());
    }

    public Verdict evaluate(String toolName, Optional<ToolRisk> risk, boolean protectedAction) {
        if (deny.contains(toolName)) {
            return Verdict.DENY;
        }
        // Protected actions sit above allow so no rule can switch the gate off
        if (protectedAction || ask.contains(toolName)) {
            return Verdict.ASK;
        }
        if (allow.contains(toolName)) {
            return Verdict.RUN;
        }
        return risk.equals(Optional.of(ToolRisk.READ_ONLY)) ? Verdict.RUN : Verdict.ASK;
    }

    /** Denied tools are never offered to the model. */
    public boolean isOffered(String toolName) {
        return !deny.contains(toolName);
    }
}

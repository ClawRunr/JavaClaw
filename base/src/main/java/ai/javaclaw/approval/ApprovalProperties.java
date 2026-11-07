package ai.javaclaw.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@code agent.approval.*}: which tool calls run, which are refused, and how long an approval may
 * wait. Invalid values fail binding, so the application refuses to start rather than running with
 * an approval setup the owner did not intend.
 *
 * @param timeout     how long a pending approval waits for the owner before it counts as denied;
 *                    more than zero and at most {@link #MAX_TIMEOUT}
 * @param askFallback what happens to a call that needs approval when nobody can be asked
 * @param rules       tool-name rules, evaluated deny, then ask, then allow
 */
@ConfigurationProperties("agent.approval")
public record ApprovalProperties(Duration timeout, AskFallback askFallback, Rules rules) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Upper bound for {@link #timeout}. The approval wait happens between two model calls while the
     * web chat's WebSocket stays open, so it must stay well inside how long a person reasonably
     * keeps a chat tab waiting and inside any proxy idle timeout in front of it.
     */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    public ApprovalProperties {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("agent.approval.timeout must be positive, but was " + timeout);
        }
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("agent.approval.timeout must be at most " + MAX_TIMEOUT
                    + ", but was " + timeout);
        }
        askFallback = askFallback == null ? AskFallback.DENY : askFallback;
        rules = rules == null ? Rules.NONE : rules;
    }

    /** Defaults: two-minute timeout, deny when nobody can be asked, no rules. */
    public static ApprovalProperties defaults() {
        return new ApprovalProperties(null, null, null);
    }

    /** What a call that needs approval does when nobody can be asked. */
    public enum AskFallback {
        /** Refuse it; the model is told why. */
        DENY,
        /** Run it anyway — for owners who want scheduled tasks to keep working unattended. */
        ALLOW
    }

    /**
     * Tool names, matched exactly. A name in {@code deny} is never run and never offered to the
     * model; {@code ask} always asks; {@code allow} runs without asking.
     */
    public record Rules(List<String> allow, List<String> ask, List<String> deny) {

        public static final Rules NONE = new Rules(null, null, null);

        public Rules {
            allow = clean(allow);
            ask = clean(ask);
            deny = clean(deny);
        }

        private static List<String> clean(List<String> names) {
            return names == null ? List.of() : names.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .toList();
        }
    }
}

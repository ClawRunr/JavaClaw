package ai.javaclaw.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@code agent.approval.*}. Invalid values fail binding, so the application refuses to start
 * rather than running with an approval setup the owner did not intend. The default {@code allow}
 * list lives in {@code application.yaml}; without configuration every tool asks.
 *
 * @param timeout how long a pending approval waits before the call is denied; more than zero and
 *                at most {@link #MAX_TIMEOUT}
 * @param allow   tool names that run without asking
 */
@ConfigurationProperties("agent.approval")
public record ApprovalProperties(Duration timeout, List<String> allow) {

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
        allow = allow == null ? List.of() : allow.stream().filter(Objects::nonNull).map(String::trim)
                .filter(name -> !name.isEmpty()).toList();
    }

    /** Two-minute timeout, nothing allowed. */
    public static ApprovalProperties defaults() {
        return new ApprovalProperties(null, null);
    }
}

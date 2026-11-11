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
 * <p>The defaults live in {@code application.yaml}, not here: without configuration nothing is
 * allowed, nothing is denied and only the runtime configuration file is protected.
 *
 * @param timeout        how long a pending approval waits for the owner before it counts as
 *                       denied; more than zero and at most {@link #MAX_TIMEOUT}
 * @param allow          tool names that run without asking
 * @param deny           tool names that never run and are not offered to the model
 * @param protectedPaths paths and file names a call always asks about, whatever {@code allow}
 *                       says; see {@link ProtectedPaths}
 */
@ConfigurationProperties("agent.approval")
public record ApprovalProperties(Duration timeout, List<String> allow, List<String> deny, List<String> protectedPaths) {

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
        allow = clean(allow);
        deny = clean(deny);
        protectedPaths = clean(protectedPaths);
    }

    /** Two-minute timeout, nothing allowed, nothing denied, no protected paths beyond the config file. */
    public static ApprovalProperties defaults() {
        return new ApprovalProperties(null, null, null, null);
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}

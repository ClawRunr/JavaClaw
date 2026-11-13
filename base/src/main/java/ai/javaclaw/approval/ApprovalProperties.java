package ai.javaclaw.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

/**
 * {@code agent.approval.*}. The default {@code allow} list lives in {@code application.yaml};
 * without configuration every tool asks.
 *
 * @param allow tool names that run without asking
 */
@ConfigurationProperties("agent.approval")
public record ApprovalProperties(List<String> allow) {

    public ApprovalProperties {
        allow = allow == null ? List.of() : allow.stream().filter(Objects::nonNull).map(String::trim)
                .filter(name -> !name.isEmpty()).toList();
    }

    /** Nothing allowed. */
    public static ApprovalProperties defaults() {
        return new ApprovalProperties(null);
    }
}

package ai.javaclaw.approval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the tool approval gate. See {@link ApprovalGate}. */
@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfiguration {

    @Bean
    PendingApprovals pendingApprovals() {
        return new PendingApprovals();
    }

    @Bean
    ApprovalGate approvalGate(ApprovalProperties properties, PendingApprovals pendingApprovals) {
        return new ApprovalGate(properties, pendingApprovals, Clock.systemUTC());
    }
}

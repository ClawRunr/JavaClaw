package ai.javaclaw.approval;

import ai.javaclaw.configuration.ConfigurationManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Wires the tool approval gate. See {@link ApprovalGate}. */
@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfiguration {

    @Bean
    DefaultToolRisks defaultToolRisks() {
        return new DefaultToolRisks();
    }

    @Bean
    ToolRiskRegistry toolRiskRegistry(List<ToolRiskContributor> contributors) {
        return new ToolRiskRegistry(contributors);
    }

    @Bean
    PendingApprovals pendingApprovals() {
        return new PendingApprovals();
    }

    @Bean
    ApprovalGate approvalGate(ApprovalProperties properties, ToolRiskRegistry toolRiskRegistry,
                              ConfigurationManager configurationManager, PendingApprovals pendingApprovals) {
        return new ApprovalGate(properties, toolRiskRegistry,
                new ProtectedActions(configurationManager.getConfigPath()), pendingApprovals, Clock.systemUTC());
    }
}

package ai.javaclaw.approval;

import ai.javaclaw.configuration.ConfigurationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.time.Clock;

/** Wires the tool approval gate. See {@link ApprovalGate}. */
@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfiguration {

    @Bean
    PendingApprovals pendingApprovals() {
        return new PendingApprovals();
    }

    /** @param workspace {@code agent.workspace}, whose default lives in {@code application.yaml} */
    @Bean
    ApprovalGate approvalGate(ApprovalProperties properties, ConfigurationManager configurationManager,
                              PendingApprovals pendingApprovals,
                              @Value("${agent.workspace}") Resource workspace) throws IOException {
        ProtectedPaths protectedPaths = new ProtectedPaths(configurationManager.getConfigPath(),
                properties.protectedPaths(), workspace.getFilePath());
        return new ApprovalGate(properties, protectedPaths, pendingApprovals, Clock.systemUTC());
    }
}

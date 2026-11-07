package ai.javaclaw.approval;

import ai.javaclaw.configuration.ConfigurationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

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
                              ConfigurationManager configurationManager, PendingApprovals pendingApprovals,
                              @Value("${agent.workspace:file:./workspace/}") Resource workspace) throws IOException {
        // The agent's memory is replayed from here: a rewritten history could put words in the owner's mouth
        Map<Path, String> protectedDirectories = Map.of(
                workspace.getFilePath().resolve("conversations"), "workspace/conversations");
        return new ApprovalGate(properties, toolRiskRegistry,
                new ProtectedActions(configurationManager.getConfigPath(), protectedDirectories),
                pendingApprovals, Clock.systemUTC());
    }
}

package ai.javaclaw.tools.search;

import ai.javaclaw.approval.ApprovalGate;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DynamicToolDiscoveryProperties.class)
@ConditionalOnProperty(name = "javaclaw.tools.dynamic-discovery.enabled", havingValue = "true", matchIfMissing = true)
public class DynamicToolDiscoveryConfiguration {

    @Bean(destroyMethod = "close")
    public LuceneToolIndex toolIndex(DynamicToolDiscoveryProperties properties) {
        return new LuceneToolIndex(properties.luceneMinScoreThreshold());
    }

    /** Discovered tools run through the same approval gate as every other tool. */
    @Bean
    public ToolSearchToolCallingAdvisor toolSearchToolCallingAdvisor(ToolIndex toolIndex,
                                                                     DynamicToolDiscoveryProperties properties,
                                                                     ApprovalGate approvalGate,
                                                                     ObjectProvider<ToolCallingManager> toolCallingManagers) {
        return ToolSearchToolCallingAdvisor.builder()
                .toolCallingManager(approvalGate.toolCallingManager(toolCallingManagers))
                .toolIndex(toolIndex)
                .maxResults(properties.maxResults())
                .build();
    }
}

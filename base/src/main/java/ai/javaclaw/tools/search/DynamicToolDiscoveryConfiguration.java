package ai.javaclaw.tools.search;

import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
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

    @Bean
    public ToolSearchToolCallingAdvisor toolSearchToolCallingAdvisor(ToolIndex toolIndex,
                                                                     DynamicToolDiscoveryProperties properties) {
        return ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .maxResults(properties.maxResults())
                .build();
    }
}

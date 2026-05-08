package ai.javaclaw.tools.search;

import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DynamicToolDiscoveryProperties.class)
@ConditionalOnProperty(name = "javaclaw.tools.dynamic-discovery.enabled", havingValue = "true", matchIfMissing = true)
public class DynamicToolDiscoveryConfiguration {

    @Bean(destroyMethod = "close")
    public ToolSearcher toolSearcher(DynamicToolDiscoveryProperties properties) {
        return new LuceneToolSearcher(properties.luceneMinScoreThreshold());
    }

    @Bean
    public ToolSearchToolCallAdvisor toolSearchToolCallAdvisor(ToolSearcher toolSearcher,
                                                               DynamicToolDiscoveryProperties properties) {
        return ToolSearchToolCallAdvisor.builder()
                .toolSearcher(toolSearcher)
                .maxResults(properties.maxResults())
                .build();
    }
}

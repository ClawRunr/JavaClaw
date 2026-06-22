package ai.javaclaw.tools.search;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "javaclaw.tools.dynamic-discovery")
public record DynamicToolDiscoveryProperties(
        Boolean enabled,
        Integer maxResults,
        Float luceneMinScoreThreshold
) {

    public DynamicToolDiscoveryProperties {
        if (enabled == null) enabled = true;
        if (maxResults == null) maxResults = 8;
        if (luceneMinScoreThreshold == null) luceneMinScoreThreshold = 0.25f;
    }
}

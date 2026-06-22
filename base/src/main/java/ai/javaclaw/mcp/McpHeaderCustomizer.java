package ai.javaclaw.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(McpConnectionsProperties.class)
public class McpHeaderCustomizer implements McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> {

    private final McpConnectionsProperties properties;

    public McpHeaderCustomizer(McpConnectionsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(String name, HttpClientStreamableHttpTransport.Builder builder) {
        McpConnectionsProperties.Connection connection = properties.connections().get(name);
        if (connection != null && !connection.headers().isEmpty()) {
            builder.httpRequestCustomizer((r, method, endpoint, body, context) ->
                connection.headers().forEach(r::header));
        }
    }
}

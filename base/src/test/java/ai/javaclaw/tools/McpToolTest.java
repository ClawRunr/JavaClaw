package ai.javaclaw.tools;

import ai.javaclaw.configuration.ConfigurationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class McpToolTest {

    @Mock
    ConfigurationManager configurationManager;

    McpTool mcpTool;

    @BeforeEach
    void setUpMcpTool() {
        mcpTool = new McpTool(configurationManager);
    }

    @Test
    void addStdioMcpServerWithArgsAndEnvVariables() throws IOException {
        String result = mcpTool.addStdioMcpServer("brave-search", "npx -y @modelcontextprotocol/server-brave-search", "BRAVE_API_KEY=123" + System.lineSeparator() + "LOG=DEBUG");

        assertThat(result).isEqualTo("MCP server 'brave-search' (stdio) added successfully. Restart the application for it to take effect.");
        verify(configurationManager).updateProperties(Map.of(
                "spring.ai.mcp.client.stdio.connections.brave-search.command", "npx",
                "spring.ai.mcp.client.stdio.connections.brave-search.args", Arrays.asList("-y", "@modelcontextprotocol/server-brave-search"),
                "spring.ai.mcp.client.stdio.connections.brave-search.env.BRAVE_API_KEY", "123",
                "spring.ai.mcp.client.stdio.connections.brave-search.env.LOG", "DEBUG"
        ));
    }

    @Test
    void addStdioMcpServerWithoutArgsAndEnvVariables() throws IOException {
        String result = mcpTool.addStdioMcpServer("brave-search", "/path/to/my-custom-server", "");

        assertThat(result).isEqualTo("MCP server 'brave-search' (stdio) added successfully. Restart the application for it to take effect.");
        verify(configurationManager).updateProperties(Map.of(
                "spring.ai.mcp.client.stdio.connections.brave-search.command", "/path/to/my-custom-server",
                "spring.ai.mcp.client.stdio.connections.brave-search.args", Collections.emptyList()
        ));
    }

    @Test
    void addStdioMcpServerWithWrongName() {
        String result = mcpTool.addStdioMcpServer("br@ve-search", "/path/to/my-custom-server", "");

        assertThat(result).isEqualTo("Error: Server name may only contain letters, numbers, hyphens and underscores.");
        verifyNoInteractions(configurationManager);
    }
}
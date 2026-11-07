package ai.javaclaw.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolDirectoryTest {

    @Test
    void findsTheServerAndTheServersOwnNameOfAnOfferedTool() {
        McpSyncClient github = client("github", "create_issue", "list_issues");
        McpSyncClient files = client("files", "read_file");
        McpToolDirectory directory = directory(List.of(github, files),
                callback("github_create_issue", "create_issue"), callback("files_read_file", "read_file"));

        assertThat(directory.find("github_create_issue")).contains(new McpToolDirectory.McpTool("github", "create_issue"));
        assertThat(directory.find("files_read_file")).contains(new McpToolDirectory.McpTool("files", "read_file"));
        assertThat(directory.find("WebFetch")).isEmpty();

        // Each server's tools are listed once
        directory.find("github_create_issue");
        verify(github, times(1)).listTools();
    }

    @Test
    void aServerThatCannotListItsToolsIsLeftUnnamed() {
        McpSyncClient broken = mock(McpSyncClient.class);
        when(broken.listTools()).thenThrow(new IllegalStateException("down"));
        McpToolDirectory directory = directory(List.of(broken), callback("x_ping", "ping"));

        assertThat(directory.find("x_ping")).contains(new McpToolDirectory.McpTool("unknown server", "ping"));
    }

    private static McpToolDirectory directory(List<McpSyncClient> clients, ToolCallback... callbacks) {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return new McpToolDirectory(() -> provider, () -> clients);
    }

    private static SyncMcpToolCallback callback(String offeredName, String originalName) {
        SyncMcpToolCallback callback = mock(SyncMcpToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(offeredName).description("d")
                .inputSchema("{}").build());
        when(callback.getOriginalToolName()).thenReturn(originalName);
        return callback;
    }

    private static McpSyncClient client(String server, String... tools) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation(server, "1.0"));
        List<McpSchema.Tool> listed = java.util.Arrays.stream(tools)
                .map(name -> McpSchema.Tool.builder().name(name).inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null)).build())
                .toList();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(listed, null));
        return client;
    }
}

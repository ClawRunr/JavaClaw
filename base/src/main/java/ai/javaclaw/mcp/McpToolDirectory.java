package ai.javaclaw.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Tells which MCP server a tool the agent was offered comes from, so an approval request can name
 * the server and the server's own tool name instead of the prefixed name the model sees.
 *
 * <p>Only reads what the MCP clients already know; a failure to list a server's tools just means
 * the request falls back to showing the tool's arguments.
 */
@Component
public class McpToolDirectory {

    private static final Logger log = LoggerFactory.getLogger(McpToolDirectory.class);

    /** A tool of an MCP server: the server's name and the tool's name on that server. */
    public record McpTool(String server, String tool) {
    }

    private final Supplier<SyncMcpToolCallbackProvider> callbacks;
    private final Supplier<List<McpSyncClient>> clients;
    private final Map<McpSyncClient, List<String>> toolsByClient = new ConcurrentHashMap<>();

    @Autowired
    public McpToolDirectory(ObjectProvider<SyncMcpToolCallbackProvider> callbacks,
                            ObjectProvider<List<McpSyncClient>> clients) {
        this(callbacks::getIfAvailable, () -> clients.getIfAvailable(List::of));
    }

    /**
     * @param callbacks the tools the agent is offered from MCP servers, or {@code null} without any
     * @param clients   the MCP clients, one per server
     */
    McpToolDirectory(Supplier<SyncMcpToolCallbackProvider> callbacks, Supplier<List<McpSyncClient>> clients) {
        this.callbacks = callbacks;
        this.clients = clients;
    }

    /** @param toolName the tool's name as the agent was offered it */
    public Optional<McpTool> find(String toolName) {
        try {
            SyncMcpToolCallbackProvider provider = callbacks.get();
            if (provider == null) {
                return Optional.empty();
            }
            for (ToolCallback callback : provider.getToolCallbacks()) {
                if (callback instanceof SyncMcpToolCallback mcp && callback.getToolDefinition().name().equals(toolName)) {
                    String original = mcp.getOriginalToolName();
                    return Optional.of(new McpTool(serverOf(original).orElse("unknown server"), original));
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not look up MCP tool {}", toolName, e);
        }
        return Optional.empty();
    }

    private Optional<String> serverOf(String originalToolName) {
        List<McpSyncClient> all = clients.get();
        for (McpSyncClient client : all == null ? List.<McpSyncClient>of() : all) {
            if (toolsOf(client).contains(originalToolName)) {
                McpSchema.Implementation server = client.getServerInfo();
                return Optional.ofNullable(server == null ? null : server.name());
            }
        }
        return Optional.empty();
    }

    /** A server's tool names, listed once; a failed listing is tried again next time. */
    private List<String> toolsOf(McpSyncClient client) {
        List<String> known = toolsByClient.get(client);
        if (known != null) {
            return known;
        }
        try {
            List<String> names = new ArrayList<>();
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                page.tools().forEach(tool -> names.add(tool.name()));
                cursor = page.nextCursor();
            } while (cursor != null);
            toolsByClient.put(client, List.copyOf(names));
            return names;
        } catch (RuntimeException e) {
            log.debug("Could not list the tools of an MCP server", e);
            return List.of();
        }
    }
}

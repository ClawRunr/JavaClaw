package ai.javaclaw.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelSearchConfigurationTest {

    @Test
    void exampleLoadsAndDispatchesSearchAndFetchWithoutCredentials() throws Exception {
        List<String> methods = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        JsonMapper mapper = JsonMapper.shared();
        server.createContext("/mcp", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("User-Agent"))
                    .isEqualTo("JavaClaw/1.0 (Parallel Search MCP)");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            var request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", "2025-03-26",
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "parallel-fixture", "version", "1.0"));
                case "tools/list" -> Map.of("tools", List.of(
                        Map.of("name", "web_search", "description", "Search the web",
                                "inputSchema", Map.of("type", "object")),
                        Map.of("name", "web_fetch", "description", "Fetch pages",
                                "inputSchema", Map.of("type", "object"))));
                case "tools/call" -> {
                    String name = request.path("params").path("name").asText();
                    assertThat(name).isIn("web_search", "web_fetch");
                    var arguments = request.path("params").path("arguments");
                    if (name.equals("web_search")) {
                        assertThat(arguments.path("objective").asText()).isEqualTo("Find JavaClaw documentation");
                        assertThat(arguments.path("search_queries").get(0).asText()).isEqualTo("JavaClaw MCP");
                    } else {
                        assertThat(arguments.path("urls").get(0).asText()).isEqualTo("https://github.com/ClawRunr/JavaClaw");
                    }
                    yield Map.of("content", List.of(Map.of("type", "text", "text",
                            "JavaClaw supports MCP. https://github.com/ClawRunr/JavaClaw")), "isError", false);
                }
                default -> throw new IllegalStateException("Unexpected method: " + method);
            };
            byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            runner().withPropertyValues("spring.ai.mcp.client.streamable-http.connections.parallel.url=http://127.0.0.1:"
                    + server.getAddress().getPort()).run(context -> {
                assertThat(context).hasNotFailed();
                invoke(context.getBean(SyncMcpToolCallbackProvider.class));
            });
            assertThat(methods).contains("initialize", "tools/list", "tools/call");
            assertThat(methods.stream().filter("tools/call"::equals)).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JAVACLAW_PARALLEL_LIVE_TEST", matches = "true")
    void liveExampleDispatchesSearchAndFetch() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            invoke(context.getBean(SyncMcpToolCallbackProvider.class));
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StreamableHttpHttpClientTransportAutoConfiguration.class,
                        McpClientAutoConfiguration.class, McpToolCallbackAutoConfiguration.class))
                .withUserConfiguration(McpHeaderCustomizer.class)
                .withInitializer(context -> {
                    try {
                        var resource = new FileSystemResource("../examples/parallel-search.yaml");
                        new YamlPropertySourceLoader().load("parallel-example", resource)
                                .forEach(context.getEnvironment().getPropertySources()::addLast);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private void invoke(SyncMcpToolCallbackProvider provider) {
        ToolCallback[] tools = provider.getToolCallbacks();
        assertThat(tools).hasSize(2);
        ToolCallback search = Arrays.stream(tools).filter(t -> t.getToolDefinition().name().endsWith("web_search")).findFirst().orElseThrow();
        ToolCallback fetch = Arrays.stream(tools).filter(t -> t.getToolDefinition().name().endsWith("web_fetch")).findFirst().orElseThrow();
        assertThat(search.call("{\"objective\":\"Find JavaClaw documentation\",\"search_queries\":[\"JavaClaw MCP\"]}"))
                .contains("JavaClaw", "https://github.com/ClawRunr/JavaClaw");
        assertThat(fetch.call("{\"urls\":[\"https://github.com/ClawRunr/JavaClaw\"]}"))
                .contains("JavaClaw");
    }
}

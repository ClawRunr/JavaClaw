package ai.javaclaw.providers.claudecode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * A synthetic Anthropic Messages endpoint on loopback: it records every request and answers with a
 * scripted SSE stream. Real Claude Code talks to it through the relay; no credentials or inference.
 */
final class FakeAnthropicUpstream implements AutoCloseable {

    static final String TEXT = "FIRST λ";

    enum Mode {TEXT, TOOL, THINKING_TOOL, MAX_TOKENS, REFUSAL, RATE_LIMITED, HANG}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Deque<Mode> script = new ConcurrentLinkedDeque<>();
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch disconnected = new CountDownLatch(1);

    FakeAnthropicUpstream(Mode... modes) throws IOException {
        script.addAll(List.of(modes));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requests() {
        return bodies.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod()) || !exchange.getRequestURI().getPath().endsWith("/v1/messages")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        bodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
        entered.countDown();
        Mode mode = script.size() > 1 ? script.poll() : script.peek();
        if (mode == Mode.RATE_LIMITED) {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"fixture limit\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        if (mode == Mode.HANG) {
            // SSE comments until the relay closes this upstream connection; the write then fails.
            try {
                for (int i = 0; i < 600; i++) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException e) {
                disconnected.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        try (out) {
            for (ObjectNode event : events(mode)) {
                out.write(("event: " + event.get("type").stringValue() + "\ndata: " + JSON.writeValueAsString(event) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            disconnected.countDown();
        }
    }

    static ObjectNode usage() {
        return JSON.createObjectNode().put("input_tokens", 101).put("output_tokens", 37)
                .put("cache_read_input_tokens", 211).put("cache_creation_input_tokens", 313);
    }

    private static List<ObjectNode> events(Mode mode) {
        List<ObjectNode> blocks = new ArrayList<>();
        if (mode == Mode.THINKING_TOOL) {
            blocks.add(JSON.createObjectNode().put("type", "thinking").put("thinking", "fixture thought").put("signature", "fixture-signature"));
        } else {
            blocks.add(JSON.createObjectNode().put("type", "text").put("text", TEXT));
        }
        if (mode == Mode.TOOL || mode == Mode.THINKING_TOOL) {
            ObjectNode tool = JSON.createObjectNode().put("type", "tool_use").put("id", "toolu_fixture_1")
                    .put("name", "mcp__javaclaw__read_file");
            tool.putObject("input").put("path", "fixture.txt");
            blocks.add(tool);
        }
        List<ObjectNode> events = new ArrayList<>();
        ObjectNode start = JSON.createObjectNode().put("type", "message_start");
        ObjectNode message = start.putObject("message").put("id", "msg_fixture").put("type", "message").put("role", "assistant")
                .put("model", "claude-sonnet-4-6");
        message.putArray("content");
        message.set("usage", usage());
        events.add(start);
        for (int i = 0; i < blocks.size(); i++) {
            ObjectNode block = blocks.get(i);
            String type = block.get("type").stringValue();
            ObjectNode empty = block.deepCopy();
            ObjectNode blockStart = JSON.createObjectNode().put("type", "content_block_start").put("index", i);
            blockStart.set("content_block", empty);
            List<ObjectNode> deltas = new ArrayList<>();
            switch (type) {
                case "text" -> {
                    empty.put("text", "");
                    deltas.add(JSON.createObjectNode().put("type", "text_delta").put("text", block.get("text").stringValue()));
                }
                case "thinking" -> {
                    empty.put("thinking", "").put("signature", "");
                    deltas.add(JSON.createObjectNode().put("type", "thinking_delta").put("thinking", block.get("thinking").stringValue()));
                    deltas.add(JSON.createObjectNode().put("type", "signature_delta").put("signature", block.get("signature").stringValue()));
                }
                default -> {
                    empty.putObject("input");
                    deltas.add(JSON.createObjectNode().put("type", "input_json_delta").put("partial_json", JSON.writeValueAsString(block.get("input"))));
                }
            }
            events.add(blockStart);
            for (ObjectNode delta : deltas) {
                ObjectNode event = JSON.createObjectNode().put("type", "content_block_delta").put("index", i);
                event.set("delta", delta);
                events.add(event);
            }
            events.add(JSON.createObjectNode().put("type", "content_block_stop").put("index", i));
        }
        String stopReason = switch (mode) {
            case TOOL, THINKING_TOOL -> "tool_use";
            case MAX_TOKENS -> "max_tokens";
            case REFUSAL -> "refusal";
            default -> "end_turn";
        };
        ObjectNode messageDelta = JSON.createObjectNode().put("type", "message_delta");
        messageDelta.putObject("delta").put("stop_reason", stopReason).putNull("stop_sequence");
        messageDelta.set("usage", usage());
        events.add(messageDelta);
        events.add(JSON.createObjectNode().put("type", "message_stop"));
        return events;
    }

    /** Every content block of every message in a recorded request body. */
    static List<JsonNode> blocks(JsonNode body) {
        List<JsonNode> blocks = new ArrayList<>();
        for (JsonNode message : body.get("messages")) {
            if (message.get("content") instanceof ArrayNode content) {
                content.forEach(blocks::add);
            }
        }
        return blocks;
    }
}

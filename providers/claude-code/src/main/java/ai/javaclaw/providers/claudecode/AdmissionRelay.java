package ai.javaclaw.providers.claudecode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Request-scoped loopback relay between one Claude Code process and the Anthropic API.
 * <p>
 * Claude Code is started with {@code ANTHROPIC_BASE_URL} pointing here. The relay forwards only the
 * first Messages request upstream and denies every later attempt locally, so Claude Code's own
 * retry/recovery can never turn one JavaClaw model call into several billed requests. Claude Code's
 * authorization headers pass through memory to the upstream and are never logged or stored.
 * <p>
 * The same server answers the inert MCP endpoint that advertises JavaClaw's tool inventory to
 * Claude Code: tools are listed, but a call is always refused, because JavaClaw executes tools itself.
 */
final class AdmissionRelay implements Closeable {

    static final String CONSUMED = "JAVACLAW_MODEL_ADMISSION_CONSUMED";

    private static final Logger log = LoggerFactory.getLogger(AdmissionRelay.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient UPSTREAM = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    // Hop-by-hop headers, headers HttpClient sets itself, and the encoding the relay pins to identity.
    private static final Set<String> REQUEST_HEADERS_DROPPED = Set.of("host", "connection", "content-length",
            "transfer-encoding", "proxy-authorization", "proxy-connection", "accept-encoding", "expect", "upgrade",
            "keep-alive", "te", "origin");
    private static final Set<String> RESPONSE_HEADERS_DROPPED = Set.of("connection", "transfer-encoding", "server",
            "date", "content-length", "keep-alive");
    private static final int MAX_ERROR_BODY = 65536;

    private final URI upstream;
    private final Duration timeout;
    private final ArrayNode queried;
    private final ArrayNode toolManifest;
    private final String prefix;
    private final HttpServer server;
    private final SseCapture capture = new SseCapture();
    private final ByteArrayOutputStream errorBody = new ByteArrayOutputStream();
    private final Set<Closeable> active = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();

    private boolean cancelled;
    private boolean used;
    private int denied;
    private Integer status;
    private String requestId;
    private String failure;
    private String unrestored;

    /**
     * @param upstream     the real API origin ({@code https://api.anthropic.com}; loopback HTTP only for test fixtures)
     * @param queried      the content of the frame JavaClaw queried, used to keep cache breakpoints stable
     * @param toolManifest the MCP tool inventory ({@code name}, {@code description}, {@code inputSchema})
     */
    AdmissionRelay(URI upstream, Duration timeout, ArrayNode queried, ArrayNode toolManifest) throws IOException {
        boolean loopbackFixture = "http".equals(upstream.getScheme()) && upstream.getHost() != null
                && InetAddress.getByName(upstream.getHost()).isLoopbackAddress();
        if (!("https".equals(upstream.getScheme()) || loopbackFixture) || upstream.getHost() == null
                || upstream.getUserInfo() != null || upstream.getQuery() != null || upstream.getFragment() != null) {
            throw new IllegalArgumentException("Claude Code upstream must be HTTPS or a loopback HTTP fixture");
        }
        this.upstream = upstream;
        this.timeout = timeout;
        this.queried = queried;
        this.toolManifest = toolManifest;
        byte[] route = new byte[32];
        RANDOM.nextBytes(route);
        this.prefix = "/admit/" + Base64.getUrlEncoder().withoutPadding().encodeToString(route);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.setExecutor(ClaudeCodeCli.VIRTUAL_THREADS);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** The base URL Claude Code gets as {@code ANTHROPIC_BASE_URL}. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    String mcpUrl() {
        return url() + "/mcp";
    }

    synchronized boolean used() {
        return used;
    }

    synchronized int denied() {
        return denied;
    }

    synchronized Integer status() {
        return status;
    }

    synchronized String requestId() {
        return requestId;
    }

    synchronized String failure() {
        return failure;
    }

    synchronized String unrestored() {
        return unrestored;
    }

    SseCapture capture() {
        return capture;
    }

    /** The upstream's own message for a non-200 answer, empty when none was captured. */
    String errorText() {
        String text;
        synchronized (errorBody) {
            text = errorBody.toString(StandardCharsets.UTF_8);
        }
        try {
            JsonNode message = JSON.readTree(text).path("error").path("message");
            return message.isString() ? message.stringValue() : text;
        } catch (RuntimeException e) {
            return text;
        }
    }

    /** Stops the in-flight upstream request and refuses every later one. */
    void abort() {
        synchronized (this) {
            cancelled = true;
        }
        pending.forEach(future -> future.cancel(true));
        active.forEach(AdmissionRelay::closeQuietly);
    }

    @Override
    public void close() {
        abort();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        Closeable connection = exchange::close;
        active.add(connection);
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            // A browser page must never reach the loopback relay (DNS rebinding, CSRF).
            if (exchange.getRequestHeaders().containsKey("Origin")) {
                respond(exchange, 404, null);
            } else if (path.equals(prefix + "/v1/messages") && "POST".equals(exchange.getRequestMethod())) {
                admit(exchange);
            } else if (path.equals(prefix + "/mcp")) {
                inertMcp(exchange);
            } else {
                // Includes native's warm-up probe of the base URL.
                respond(exchange, 404, null);
            }
        } catch (IOException | RuntimeException e) {
            // The client went away; nothing to answer.
        } finally {
            active.remove(connection);
        }
    }

    private void admit(HttpExchange exchange) throws IOException {
        synchronized (this) {
            if (cancelled || used) {
                denied++;
                respond(exchange, 400, ("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\""
                        + CONSUMED + "\"}}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            used = true;
        }
        InputStream upstreamBody = null;
        try {
            byte[] payload = exchange.getRequestBody().readAllBytes();
            StringBuilder mismatch = new StringBuilder();
            payload = RelayPayloads.prepare(payload, queried, mismatch);
            if (!mismatch.isEmpty()) {
                synchronized (this) {
                    unrestored = mismatch.toString();
                }
            }
            String query = exchange.getRequestURI().getRawQuery();
            String base = upstream.toString().replaceAll("/+$", "");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/messages" + (query != null ? "?" + query : "")))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            // Request identity and payload stay native; only HTTP transfer details change.
            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                if (!REQUEST_HEADERS_DROPPED.contains(header.getKey().toLowerCase())) {
                    header.getValue().forEach(value -> request.header(header.getKey(), value));
                }
            }
            request.header("Accept-Encoding", "identity");
            CompletableFuture<HttpResponse<InputStream>> call = UPSTREAM.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            pending.add(call);
            synchronized (this) {
                if (cancelled) {
                    call.cancel(true);
                }
            }
            HttpResponse<InputStream> response = call.join();
            pending.remove(call);
            upstreamBody = response.body();
            active.add(upstreamBody);
            synchronized (this) {
                status = response.statusCode();
                requestId = response.headers().firstValue("request-id").or(() -> response.headers().firstValue("x-request-id")).orElse(null);
            }
            response.headers().map().forEach((name, values) -> {
                if (!name.startsWith(":") && !RESPONSE_HEADERS_DROPPED.contains(name.toLowerCase())) {
                    exchange.getResponseHeaders().put(name, values);
                }
            });
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(response.statusCode(), 0);
            OutputStream out = exchange.getResponseBody();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = upstreamBody.read(buffer)) != -1) {
                if (response.statusCode() == 200) {
                    capture.feed(buffer, read);
                } else {
                    synchronized (errorBody) {
                        errorBody.write(buffer, 0, Math.max(0, Math.min(read, MAX_ERROR_BODY - errorBody.size())));
                    }
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            synchronized (this) {
                failure = cause.getClass().getSimpleName();
            }
        } finally {
            if (upstreamBody != null) {
                active.remove(upstreamBody);
                closeQuietly(upstreamBody);
            }
        }
    }

    /**
     * A minimal MCP server over Streamable HTTP (JSON responses, no SSE stream): it lists JavaClaw's
     * tools so Claude Code accepts their {@code tool_use} blocks, and refuses every call.
     */
    private void inertMcp(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, null);
            return;
        }
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        ArrayNode answers = JSON.createArrayNode();
        for (JsonNode message : body.isArray() ? body : JSON.createArrayNode().add(body)) {
            if (!message.has("id") || !message.has("method")) {
                continue; // notifications and responses need no answer
            }
            ObjectNode result = JSON.createObjectNode();
            log.debug("Inert MCP request {}", message.get("method").asString(""));
            switch (message.get("method").asString("")) {
                case "initialize" -> {
                    JsonNode requested = message.path("params").path("protocolVersion");
                    result.put("protocolVersion", requested.isString() ? requested.stringValue() : "2025-06-18");
                    result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "javaclaw-inert-inventory").put("version", "1");
                }
                case "tools/list" -> result.set("tools", toolManifest);
                case "tools/call" -> {
                    result.put("isError", true);
                    result.putArray("content").addObject().put("type", "text")
                            .put("text", "Denied: Claude Code tools are inert; only JavaClaw executes tools.");
                }
                default -> {
                    // ping and anything else: an empty result
                }
            }
            ObjectNode answer = answers.addObject();
            answer.put("jsonrpc", "2.0");
            answer.set("id", message.get("id"));
            answer.set("result", result);
        }
        if (answers.isEmpty()) {
            respond(exchange, 202, null);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respond(exchange, 200, JSON.writeValueAsBytes(body.isArray() ? answers : answers.get(0)));
    }

    private static void respond(HttpExchange exchange, int code, byte[] body) throws IOException {
        if (body != null && !exchange.getResponseHeaders().containsKey("Content-Type")) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(code, body == null ? -1 : body.length);
        if (body != null) {
            exchange.getResponseBody().write(body);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            // Already closed by the peer.
        }
    }
}

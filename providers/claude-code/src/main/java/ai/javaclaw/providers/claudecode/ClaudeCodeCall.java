package ai.javaclaw.providers.claudecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One model call answered by a fresh Claude Code process.
 * <p>
 * Claude Code runs in print mode with native tools, skills, settings, memory and compaction off, and
 * {@code --max-turns 1}: it generates exactly one assistant turn and JavaClaw keeps the agent loop.
 * The conversation is replayed as stream-json frames (earlier user frames with {@code shouldQuery: false}),
 * the newest frame is answered, and the {@link AdmissionRelay} lets exactly one Messages request reach
 * the API. Authentication belongs to the CLI: JavaClaw never sees or sends the subscription credential.
 */
final class ClaudeCodeCall {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCall.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Object END = new Object();
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);
    private static final Set<Process> DRAINING = ConcurrentHashMap.newKeySet();
    // Claude Code's own error codes for errors it answers without a relayed upstream status.
    private static final Map<String, Integer> NATIVE_ERROR_STATUS = Map.of(
            "rate_limit", 429, "billing_error", 402, "authentication_failed", 401, "overloaded", 529, "server_error", 503);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> DRAINING.forEach(ClaudeCodeCall::killTree), "claude-code-drain-cleanup"));
    }

    /** What one call produced, before it is shaped into a Spring AI response. */
    record Outcome(String id, String text, List<ObjectNode> toolCalls, String finishReason, ObjectNode usage,
                   ObjectNode nativeTurn) {
    }

    private final ClaudeCodeRequest request;
    private final String command;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final Deque<String> stderr = new ArrayDeque<>();

    private boolean cancelled;
    private Process process;
    private AdmissionRelay relay;

    /**
     * @param environment the environment for Claude Code, or {@code null} to inherit JavaClaw's own
     *                    (only then are conflicting credential overrides refused)
     */
    ClaudeCodeCall(ClaudeCodeRequest request, String command, Map<String, String> environment, Duration timeout) {
        this.request = request;
        this.command = command;
        this.environment = environment;
        this.timeout = timeout;
    }

    /** Kills the Claude Code process and the in-flight upstream request; safe from any thread. */
    void cancel() {
        Process running;
        AdmissionRelay gate;
        synchronized (this) {
            cancelled = true;
            running = process;
            gate = relay;
        }
        if (gate != null) {
            gate.abort();
        }
        if (running != null) {
            killTree(running);
        }
    }

    Outcome run(Consumer<String> onText) {
        Path files = null;
        Process child = null;
        AdmissionRelay gate = null;
        Thread reader = null;
        Thread stderrDrain = null;
        boolean handedOff = false;
        try {
            Map<String, String> env = ClaudeCodeCli.childEnvironment(environment != null ? environment : System.getenv(), environment == null);
            String executable = ClaudeCodeCli.resolve(command, env)
                    .orElseThrow(() -> new ClaudeCodeException(ClaudeCodeCli.INSTALL_HINT, 503));
            // An inherited effort level would override the request; JavaClaw owns budgets, memory,
            // instructions and compaction, and the relay owns retries.
            env.remove("CLAUDE_CODE_EXTRA_BODY");
            env.remove("CLAUDE_CODE_EFFORT_LEVEL");
            env.putAll(Map.of(
                    "ENABLE_TOOL_SEARCH", "false",
                    "CLAUDE_CODE_MAX_RETRIES", "0",
                    "DISABLE_AUTO_COMPACT", "1",
                    "DISABLE_COMPACT", "1",
                    "CLAUDE_CODE_TOTAL_TOKENS_REMINDER", "off",
                    "CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1",
                    "CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1"));
            // The relay is loopback: never send Claude Code's requests to it through a configured proxy.
            String noProxy = env.getOrDefault("no_proxy", env.getOrDefault("NO_PROXY", ""));
            noProxy = (noProxy.isBlank() ? "" : noProxy + ",") + "127.0.0.1,localhost";
            env.put("NO_PROXY", noProxy);
            env.put("no_proxy", noProxy);

            gate = new AdmissionRelay(URI.create(env.getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com")),
                    timeout, request.queriedContent(), request.manifest);
            synchronized (this) {
                if (cancelled) {
                    throw cancelledError();
                }
                relay = gate;
            }
            env.put("ANTHROPIC_BASE_URL", gate.url());

            // Large prompts and tool schemas go through files: the OS limits single argv/env strings.
            files = Files.createTempDirectory("javaclaw-claude-");
            Path system = files.resolve("system.md");
            Files.writeString(system, request.system);
            Path settings = files.resolve("settings.json");
            ObjectNode settingsJson = JSON.createObjectNode();
            settingsJson.putObject("env").put("CLAUDE_CODE_EXTRA_BODY", JSON.writeValueAsString(request.extraBody));
            Files.writeString(settings, JSON.writeValueAsString(settingsJson));
            if (request.extraBody.has("max_tokens")) {
                env.put("CLAUDE_CODE_MAX_OUTPUT_TOKENS", request.extraBody.get("max_tokens").asString());
            }
            ObjectNode mcp = JSON.createObjectNode();
            mcp.putObject("mcpServers").putObject("javaclaw").put("type", "http").put("url", gate.mcpUrl());

            List<String> argv = new ArrayList<>(List.of(executable, "-p",
                    "--model", request.nativeModel,
                    "--input-format", "stream-json",
                    "--output-format", "stream-json",
                    "--verbose", "--include-partial-messages",
                    "--tools", "",
                    "--system-prompt-file", system.toString(),
                    "--settings", settings.toString(),
                    "--setting-sources", "",
                    "--strict-mcp-config",
                    "--disable-slash-commands",
                    "--max-turns", "1",
                    "--permission-mode", "dontAsk",
                    "--no-session-persistence",
                    "--mcp-config", JSON.writeValueAsString(mcp),
                    "--session-id", request.sessionId()));
            log.debug("Starting Claude Code ({}) for model {} with {} frames", executable, request.nativeModel, request.frames.size());

            synchronized (this) {
                if (cancelled) {
                    throw cancelledError();
                }
                child = ClaudeCodeCli.start(argv, env, ClaudeCodeWorkdir.get());
                process = child;
            }
            Process started = child;
            stderrDrain = Thread.ofVirtual().name("claude-code-stderr").start(() -> drainStderr(started));
            BlockingQueue<Object> events = new LinkedBlockingQueue<>();
            reader = Thread.ofVirtual().name("claude-code-stdout").start(() -> readEvents(started, events));

            replayHistory(child, events);
            closeStdin(child);

            List<JsonNode> assistants = new ArrayList<>();
            List<JsonNode> results = new ArrayList<>();
            boolean stopped = false;
            StringBuilder emitted = new StringBuilder();
            String nativeError = null;
            String nativeErrorCode = null;
            boolean fast = false;
            while (true) {
                Object next = receive(events);
                if (next == END) {
                    break;
                }
                JsonNode event = (JsonNode) next;
                switch (event.path("type").asString("")) {
                    case "assistant" -> {
                        if (truthy(event.path("error")) || truthy(event.path("message").path("error"))) {
                            nativeError = joinText(event.path("message").path("content"));
                            nativeErrorCode = event.path("error").isString() ? event.get("error").stringValue() : "";
                        } else {
                            assistants.add(event.get("message"));
                        }
                    }
                    case "result" -> {
                        results.add(event);
                        fast = fastEligible(event, gate, stopped, nativeError);
                    }
                    case "stream_event" -> {
                        JsonNode nativeEvent = event.path("event");
                        if ("message_stop".equals(nativeEvent.path("type").asString(""))) {
                            stopped = true;
                        }
                        JsonNode delta = nativeEvent.path("delta");
                        if ("text_delta".equals(delta.path("type").asString(""))) {
                            String text = delta.get("text").stringValue();
                            emitted.append(text);
                            onText.accept(text);
                        }
                    }
                    default -> {
                        // system/init, control and replay events carry nothing for the answer.
                    }
                }
                if (fast) {
                    break;
                }
            }

            Integer exitCode = null;
            if (fast) {
                // A clean admitted answer returns at native's result event; its shutdown drains in the background.
                synchronized (this) {
                    process = null;
                    relay = null;
                }
                handOff(child, gate);
                handedOff = true;
            } else {
                if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new ClaudeCodeException("Claude request timed out", null);
                }
                reader.join(1000);
                stderrDrain.join(1000);
                exitCode = child.exitValue();
            }
            if (isCancelled()) {
                throw cancelledError();
            }

            if (gate.used()) {
                if (!Integer.valueOf(200).equals(gate.status()) || !gate.capture().complete()) {
                    String first = "first upstream attempt: status " + gate.status() + ", capture "
                            + (gate.capture().complete() ? "complete" : "incomplete")
                            + (gate.failure() != null ? ", relay failure " + gate.failure() : "")
                            + ", native retries denied: " + gate.denied();
                    if (!gate.errorText().isEmpty()) {
                        first += ", upstream said: " + truncate(gate.errorText(), 500);
                    }
                    Integer status = gate.status() != null && gate.status() >= 400 ? gate.status() : null;
                    throw new ClaudeCodeException("Incomplete upstream response (" + first + ")"
                            + (nativeError != null ? ": " + nativeError : ""), status);
                }
                assistants = List.of(gate.capture().message());
                stopped = true;
            }
            boolean nativeFailureHandled = gate.denied() > 0
                    || gate.used() && "refusal".equals(assistants.getFirst().path("stop_reason").asString(""));
            if (nativeError != null && !nativeFailureHandled) {
                if ("authentication_failed".equals(nativeErrorCode) && !gate.used()) {
                    throw new ClaudeCodeException(ClaudeCodeCli.LOGGED_OUT_HINT + " (native: " + nativeError + ")" + stderrTail(), 401);
                }
                throw new ClaudeCodeException("Claude Code error: " + nativeError, NATIVE_ERROR_STATUS.get(nativeErrorCode));
            }
            if (results.size() != 1 || assistants.isEmpty() || !stopped) {
                throw new ClaudeCodeException("Incomplete Claude Code response: assistant, message_stop and one result required", null);
            }
            return outcome(results.getFirst(), assistants, emitted.toString(), onText, fast, exitCode, nativeFailureHandled, gate);
        } catch (IOException e) {
            throw new ClaudeCodeException("Claude Code request failed: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelledError();
        } finally {
            if (!handedOff) {
                cancel();
                if (gate != null) {
                    gate.close();
                }
                if (child != null) {
                    awaitQuietly(child);
                }
                joinQuietly(reader);
                joinQuietly(stderrDrain);
            }
            deleteQuietly(files);
        }
    }

    private Outcome outcome(JsonNode result, List<JsonNode> assistants, String emitted, Consumer<String> onText,
                            boolean fast, Integer exitCode, boolean nativeFailureHandled, AdmissionRelay gate) {
        List<ObjectNode> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode assistant : assistants) {
            for (JsonNode block : assistant.path("content")) {
                String type = block.path("type").asString("");
                if (type.equals("text")) {
                    text.append(block.path("text").asString(""));
                } else if (type.equals("tool_use")) {
                    String name = block.get("name").stringValue();
                    // Native sometimes drops the prefix on a tool it was offered; carry the offered name so
                    // replayed history does not teach the slip back to the model.
                    if (request.javaclawNames.containsKey(name)) {
                        ((ObjectNode) block).put("name", ClaudeCodeRequest.TOOL_PREFIX + name);
                        name = ClaudeCodeRequest.TOOL_PREFIX + name;
                    }
                    String unprefixed = name.startsWith(ClaudeCodeRequest.TOOL_PREFIX) ? name.substring(ClaudeCodeRequest.TOOL_PREFIX.length()) : name;
                    // Any other name is the model's own invention: JavaClaw's tool loop answers it with an error.
                    ObjectNode call = JSON.createObjectNode();
                    call.put("id", block.get("id").stringValue());
                    call.put("name", request.javaclawNames.getOrDefault(unprefixed, unprefixed));
                    call.set("input", block.path("input").isObject() ? block.get("input") : JSON.createObjectNode());
                    calls.add(call);
                }
            }
        }
        String subtype = result.path("subtype").asString("");
        boolean boundary = !calls.isEmpty() && subtype.equals("error_max_turns") && (fast || Integer.valueOf(1).equals(exitCode));
        boolean failed = (!fast && !Integer.valueOf(0).equals(exitCode)) || result.path("is_error").asBoolean(false) || !subtype.equals("success");
        if (!boundary && !nativeFailureHandled && failed) {
            throw new ClaudeCodeException("Claude Code request failed: " + subtype + stderrTail(), null);
        }
        JsonNode usage = gate.used() ? assistants.getFirst().get("usage") : result.get("usage");
        if (!(usage instanceof ObjectNode usageObject) || !usage.path("input_tokens").isNumber() || !usage.path("output_tokens").isNumber()) {
            throw new ClaudeCodeException("Claude Code result is missing token usage", null);
        }
        if (!emitted.contentEquals(text)) {
            if (!text.toString().startsWith(emitted)) {
                throw new ClaudeCodeException("Claude Code final text differs from its incremental stream", null);
            }
            onText.accept(text.substring(emitted.length()));
        }
        String finish;
        String answer = text.toString();
        if (assistants.stream().anyMatch(a -> "refusal".equals(a.path("stop_reason").asString("")))) {
            // A tool call the refusal cut off must not run; the native turn below still carries it for replay.
            calls.clear();
            finish = "content_filter";
            if (answer.isBlank()) {
                JsonNode details = assistants.stream().map(a -> a.path("stop_details")).filter(JsonNode::isObject).findFirst()
                        .orElse(JSON.createObjectNode());
                String explanation = details.path("explanation").asString("").strip();
                String category = details.path("category").asString("");
                answer = !explanation.isEmpty() ? explanation : !category.isEmpty() ? "Refused (category: " + category + ")" : "";
            }
        } else if (!calls.isEmpty()) {
            finish = "tool_calls";
        } else if (assistants.stream().anyMatch(a -> Set.of("max_tokens", "model_context_window_exceeded").contains(a.path("stop_reason").asString("")))) {
            finish = "length";
        } else {
            finish = "stop";
        }

        ObjectNode nativeTurn = JSON.createObjectNode();
        nativeTurn.put("version", 1);
        ArrayNode messages = nativeTurn.putArray("messages");
        assistants.forEach(messages::add);
        ObjectNode projection = nativeTurn.putObject("projection");
        projection.put("content", text.toString().strip());
        ArrayNode projected = projection.putArray("tool_calls");
        calls.forEach(call -> projected.add(call.deepCopy()));

        ObjectNode normalizedUsage = usageObject.deepCopy();
        normalizedUsage.put("upstream_requests", gate.used() ? 1 : 0);
        normalizedUsage.put("blocked_requests", gate.denied());
        if (gate.requestId() != null) {
            normalizedUsage.put("request_id", gate.requestId());
        }
        if (gate.unrestored() != null) {
            normalizedUsage.put("unrestored", gate.unrestored());
        }
        if (result.has("total_cost_usd")) {
            // Claude Code's list-price estimate, never an actual subscription charge.
            normalizedUsage.set("estimated_list_price_usd", result.get("total_cost_usd"));
        }
        String id = assistants.getLast().path("id").asString("claude-code");
        return new Outcome(id, answer, calls, finish, normalizedUsage, nativeTurn);
    }

    /** Earlier user frames are replayed with {@code shouldQuery: false}; each is acknowledged by a zero-turn result. */
    private void replayHistory(Process child, BlockingQueue<Object> events) throws IOException, InterruptedException {
        List<ObjectNode> frames = request.frames;
        for (int index = 0; index < frames.size(); index++) {
            ObjectNode frame = frames.get(index).deepCopy();
            boolean historical = "user".equals(frame.get("type").stringValue()) && index < frames.size() - 1;
            if (historical) {
                frame.put("shouldQuery", false);
            }
            send(child, JSON.writeValueAsString(frame) + "\n");
            if (!historical) {
                continue;
            }
            while (true) {
                Object next = receive(events);
                if (next == END) {
                    if (isCancelled()) {
                        throw cancelledError();
                    }
                    throw new ClaudeCodeException("Claude Code exited before acknowledging replayed history (exit="
                            + exitValueOrUnknown(child) + ")" + stderrTail(), null);
                }
                JsonNode ack = (JsonNode) next;
                if ("result".equals(ack.path("type").asString(""))) {
                    if (ack.path("num_turns").asInt(-1) != 0 || ack.path("is_error").asBoolean(false)) {
                        throw new ClaudeCodeException("Claude Code history replay not supported: expected a zero-turn acknowledgment" + stderrTail(), null);
                    }
                    break;
                }
            }
        }
    }

    /** Writes to Claude Code's stdin; a child that stops reading is killed once the timeout passes. */
    private void send(Process child, String line) throws IOException {
        AtomicBoolean stalled = new AtomicBoolean();
        CompletableFuture<Void> watchdog = CompletableFuture.runAsync(() -> {
            stalled.set(true);
            cancel();
        }, CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS));
        try {
            OutputStream stdin = child.getOutputStream();
            stdin.write(line.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            if (!isCancelled()) {
                throw e;
            }
        } finally {
            watchdog.cancel(false);
        }
        if (stalled.get()) {
            throw new ClaudeCodeException("Claude request timed out", null);
        }
        if (isCancelled()) {
            throw cancelledError();
        }
    }

    private void closeStdin(Process child) throws IOException {
        try {
            child.getOutputStream().close();
        } catch (IOException e) {
            if (!isCancelled()) {
                throw e;
            }
        }
    }

    /** The next stdout event; the idle timeout restarts with every event Claude Code emits. */
    private Object receive(BlockingQueue<Object> events) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (isCancelled()) {
                throw cancelledError();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new ClaudeCodeException("Claude request timed out", null);
            }
            Object event = events.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
            if (event instanceof RuntimeException error) {
                throw new ClaudeCodeException("Invalid Claude Code stream-json output: " + error.getMessage() + stderrTail(), null);
            }
            if (event != null) {
                return event;
            }
        }
    }

    private static void readEvents(Process child, BlockingQueue<Object> events) {
        try (BufferedReader stdout = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(JSON.readTree(line));
                } catch (RuntimeException e) {
                    // The offending line (a shim banner, a stray print) is the whole diagnosis; keep it.
                    events.add(new IllegalStateException(truncate(line, 300)));
                }
            }
        } catch (IOException e) {
            // stdout closes when the process is killed.
        } finally {
            try {
                child.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            events.add(END);
        }
    }

    private void drainStderr(Process child) {
        try (BufferedReader err = new BufferedReader(new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                synchronized (stderr) {
                    if (stderr.size() == 40) {
                        stderr.removeFirst();
                    }
                    stderr.addLast(line);
                }
            }
        } catch (IOException e) {
            // stderr closes when the process is killed.
        }
    }

    /** The first native stderr lines: a failure only seen under a service manager is undiagnosable without them. */
    private String stderrTail() {
        String text;
        synchronized (stderr) {
            text = String.join(" ", stderr.stream().map(String::strip).filter(line -> !line.isEmpty()).toList());
        }
        return text.isEmpty() ? "" : " (native stderr: " + truncate(text, 400) + ")";
    }

    private static boolean fastEligible(JsonNode result, AdmissionRelay gate, boolean stopped, String nativeError) {
        if (!gate.used() || !Integer.valueOf(200).equals(gate.status()) || !gate.capture().complete() || !stopped
                || nativeError != null || gate.denied() != 0) {
            return false;
        }
        JsonNode message = gate.capture().message();
        if ("refusal".equals(message.path("stop_reason").asString(""))) {
            return false;
        }
        String subtype = result.path("subtype").asString("");
        boolean toolUse = false;
        for (JsonNode block : message.path("content")) {
            toolUse |= "tool_use".equals(block.path("type").asString(""));
        }
        return subtype.equals("success") && !result.path("is_error").asBoolean(false) || subtype.equals("error_max_turns") && toolUse;
    }

    /** Lets a process that already answered exit on its own; late requests stay behind the consumed relay meanwhile. */
    private static void handOff(Process child, AdmissionRelay gate) {
        DRAINING.add(child);
        Thread.ofVirtual().name("claude-code-drain").start(() -> {
            try {
                if (!child.waitFor(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    killTree(child);
                    child.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                killTree(child);
            } finally {
                gate.close();
                DRAINING.remove(child);
            }
        });
    }

    private static void killTree(Process child) {
        child.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
    }

    private synchronized boolean isCancelled() {
        return cancelled;
    }

    private static ClaudeCodeException cancelledError() {
        return new ClaudeCodeException("Claude request cancelled", null);
    }

    private static boolean truthy(JsonNode node) {
        return !(node.isMissingNode() || node.isNull() || node.isBoolean() && !node.booleanValue()
                || (node.isString() || node.isContainer()) && node.isEmpty());
    }

    private static String joinText(JsonNode content) {
        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString(""))) {
                parts.add(block.path("text").asString(""));
            }
        }
        return String.join("\n", parts);
    }

    private static String exitValueOrUnknown(Process child) {
        try {
            return child.waitFor(1, TimeUnit.SECONDS) ? String.valueOf(child.exitValue()) : "running";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void awaitQuietly(Process child) {
        try {
            child.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | RuntimeException e) {
            // Best effort: the files are per request and live in the temp directory.
        }
    }
}

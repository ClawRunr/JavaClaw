package ai.javaclaw.providers.claudecode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One Spring AI {@link Prompt} translated for Claude Code: the system prompt, the conversation as
 * stream-json frames, the inert MCP tool manifest and the generation fields Claude Code receives
 * through {@code CLAUDE_CODE_EXTRA_BODY}.
 */
final class ClaudeCodeRequest {

    /** Tools reach Claude Code through the inert MCP server named {@code javaclaw}. */
    static final String TOOL_PREFIX = "mcp__javaclaw__";
    /**
     * Assistant-message metadata holding Claude Code's own assistant messages (signed thinking
     * included) next to the projection JavaClaw saw, so an unchanged turn replays byte for byte.
     */
    static final String NATIVE_TURN = "claudeCodeNativeTurn";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int NATIVE_NAME_LIMIT = 50;

    final String model;
    final String nativeModel;
    final String system;
    final List<ObjectNode> frames;
    final ObjectNode extraBody;
    final ArrayNode manifest;
    /** JavaClaw tool name to the (possibly shortened) name Claude Code sees. */
    final Map<String, String> nativeNames;
    /** The reverse of {@link #nativeNames}. */
    final Map<String, String> javaclawNames;

    private ClaudeCodeRequest(String model, String system, List<ObjectNode> frames, ObjectNode extraBody,
                              ArrayNode manifest, Map<String, String> nativeNames) {
        this.model = model;
        this.nativeModel = ClaudeCodeModels.nativeModel(model);
        this.system = system;
        this.frames = frames;
        this.extraBody = extraBody;
        this.manifest = manifest;
        this.nativeNames = nativeNames;
        this.javaclawNames = new LinkedHashMap<>();
        nativeNames.forEach((javaclaw, nativeName) -> javaclawNames.put(nativeName, javaclaw));
    }

    static ClaudeCodeRequest from(Prompt prompt, String defaultModel, ToolCallingManager toolCallingManager) {
        ChatOptions options = prompt.getOptions();
        String model = options != null && options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : defaultModel;
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("A Claude model is required");
        }

        ObjectNode body = JSON.createObjectNode();
        if (options != null && options.getMaxTokens() != null) {
            if (options.getMaxTokens() < 1) {
                throw new IllegalArgumentException("maxTokens must be a positive integer");
            }
            body.put("max_tokens", options.getMaxTokens());
        }
        if (options != null && options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            ArrayNode stops = body.putArray("stop_sequences");
            options.getStopSequences().forEach(stops::add);
        }
        // Subscription models reject sampling controls (temperature, top_p, top_k), so they are omitted.

        ArrayNode manifest = JSON.createArrayNode();
        ArrayNode tools = body.putArray("tools");
        Map<String, String> nativeNames = new LinkedHashMap<>();
        List<ToolDefinition> definitions = options instanceof ToolCallingChatOptions toolOptions
                ? toolCallingManager.resolveToolDefinitions(toolOptions) : List.of();
        for (ToolDefinition definition : definitions) {
            String name = definition.name();
            if (!TOOL_NAME.matcher(name).matches() || nativeNames.containsKey(name)) {
                throw new IllegalArgumentException("Tool names must be unique ASCII identifiers of at most 64 characters: " + name);
            }
            String nativeName = name.length() <= NATIVE_NAME_LIMIT ? name
                    : name.substring(0, 41) + "_" + sha256Hex(name).substring(0, 8);
            if (nativeNames.containsValue(nativeName)) {
                throw new IllegalArgumentException("Tool name collision: " + name + " maps to " + nativeName);
            }
            nativeNames.put(name, nativeName);
            // The manifest (inert MCP server) and the request body must advertise the same shape.
            JsonNode schema = normalizeInputSchema(definition.inputSchema());
            String description = definition.description() == null ? "" : definition.description();
            manifest.addObject().put("name", nativeName).put("description", description).set("inputSchema", schema);
            tools.addObject().put("name", TOOL_PREFIX + nativeName).put("description", description).set("input_schema", schema);
        }

        List<String> system = new ArrayList<>();
        List<ObjectNode> frames = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            switch (message) {
                case SystemMessage systemMessage -> system.add(systemMessage.getText());
                case UserMessage user -> append(frames, "user", userBlocks(user));
                case AssistantMessage assistant -> appendAssistant(frames, assistant, nativeNames);
                case ToolResponseMessage toolResponses -> {
                    ArrayNode blocks = JSON.createArrayNode();
                    for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                        blocks.addObject().put("type", "tool_result").put("tool_use_id", response.id())
                                .put("content", response.responseData() == null ? "" : response.responseData());
                    }
                    append(frames, "user", blocks);
                }
                default -> throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
            }
        }
        if (frames.isEmpty() || !"user".equals(frames.getLast().get("type").stringValue())
                || frames.getLast().get("message").get("content").isEmpty()) {
            throw new IllegalArgumentException("The conversation must end in a non-empty user or tool-result message");
        }
        return new ClaudeCodeRequest(model, String.join("\n\n", system), frames, body, manifest, nativeNames);
    }

    /** The content blocks of the frame Claude Code answers. */
    ArrayNode queriedContent() {
        return (ArrayNode) frames.getLast().get("message").get("content");
    }

    /**
     * A native session id that stays the same for every request of one conversation. Claude Code names
     * a per-session directory near the top of each request, so a new session per spawn would move the
     * cached prompt prefix on every call. Derived from what the conversation starts with.
     */
    String sessionId() {
        ArrayNode seed = JSON.createArrayNode();
        seed.add(system);
        seed.add(frames.getFirst().get("message"));
        byte[] digest = sha256(JSON.writeValueAsString(seed));
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x40);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (digest[i] & 0xff);
            low = (low << 8) | (digest[i + 8] & 0xff);
        }
        return new UUID(high, low).toString();
    }

    private static void appendAssistant(List<ObjectNode> frames, AssistantMessage assistant, Map<String, String> nativeNames) {
        ObjectNode projection = projection(assistant);
        if (assistant.getMetadata().get(NATIVE_TURN) instanceof String carried) {
            JsonNode turn = JSON.readTree(carried);
            if (turn.path("version").asInt() != 1) {
                throw new IllegalArgumentException("Unsupported Claude Code native turn version");
            }
            if (turn.get("projection").equals(projection)) {
                for (JsonNode nativeMessage : turn.get("messages")) {
                    ObjectNode copy = (ObjectNode) nativeMessage.deepCopy();
                    // Native sizes its local "prompt is too long" check from the newest assistant usage;
                    // a replayed turn would report the size of the request that made it. Never on the wire.
                    copy.remove("usage");
                    ObjectNode frame = JSON.createObjectNode().put("type", "assistant");
                    frame.set("message", copy);
                    frames.add(frame);
                }
                return;
            }
            // Edited or compacted history: replay the visible text and calls, never stale signed blocks.
        }
        ArrayNode blocks = textBlocks(assistant.getText());
        for (JsonNode call : projection.get("tool_calls")) {
            // A call outside the current inventory replays exactly as the model named it.
            String name = call.get("name").stringValue();
            if (nativeNames.containsKey(name)) {
                name = TOOL_PREFIX + nativeNames.get(name);
            }
            blocks.addObject().put("type", "tool_use").put("id", call.get("id").stringValue())
                    .put("name", name).set("input", call.get("input"));
        }
        append(frames, "assistant", blocks);
    }

    /** What JavaClaw sees of an assistant turn: trimmed text plus tool calls with parsed arguments. */
    static ObjectNode projection(AssistantMessage assistant) {
        ObjectNode projection = JSON.createObjectNode();
        projection.put("content", assistant.getText() == null ? "" : assistant.getText().strip());
        ArrayNode calls = projection.putArray("tool_calls");
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            JsonNode input;
            try {
                input = call.arguments() == null || call.arguments().isBlank() ? JSON.createObjectNode() : JSON.readTree(call.arguments());
            } catch (RuntimeException e) {
                input = JSON.createObjectNode();
            }
            calls.addObject().put("id", call.id()).put("name", call.name()).set("input", input);
        }
        return projection;
    }

    private static ArrayNode userBlocks(UserMessage user) {
        ArrayNode blocks = textBlocks(user.getText());
        for (Media media : user.getMedia()) {
            String mimeType = media.getMimeType().toString();
            Object data = media.getData();
            if (data instanceof String url && (url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8))) {
                // The transport never fetches remote content; a hint keeps the turn alive.
                blocks.addObject().put("type", "text").put("text", "[Attachment not included: remote URL " + url + "]");
                continue;
            }
            String base64 = data instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes)
                    : data instanceof String text && text.startsWith("data:") && text.contains(";base64,")
                    ? text.substring(text.indexOf(";base64,") + 8) : null;
            if (base64 == null) {
                throw new IllegalArgumentException("Only inline (base64) attachments are supported");
            }
            String type = mimeType.startsWith("image/") ? "image" : "application/pdf".equals(mimeType) ? "document" : null;
            if (type == null) {
                throw new IllegalArgumentException("Unsupported attachment type: " + mimeType);
            }
            ObjectNode source = blocks.addObject().put("type", type).putObject("source");
            source.put("type", "base64").put("media_type", mimeType).put("data", base64);
        }
        return blocks;
    }

    private static ArrayNode textBlocks(String text) {
        ArrayNode blocks = JSON.createArrayNode();
        if (text != null && !text.isEmpty()) {
            blocks.addObject().put("type", "text").put("text", text);
        }
        return blocks;
    }

    private static void append(List<ObjectNode> frames, String role, ArrayNode blocks) {
        if (role.equals("user") && !frames.isEmpty() && role.equals(frames.getLast().get("type").stringValue())) {
            ((ArrayNode) frames.getLast().get("message").get("content")).addAll(blocks);
            return;
        }
        ObjectNode frame = JSON.createObjectNode().put("type", role);
        frame.putObject("message").put("role", role).set("content", blocks);
        frames.add(frame);
    }

    /**
     * Anthropic rejects top-level {@code oneOf}/{@code allOf}/{@code anyOf} in a tool's input schema;
     * those combinators are advisory (tools re-validate their arguments), so they are dropped.
     */
    static JsonNode normalizeInputSchema(String inputSchema) {
        JsonNode parsed = inputSchema == null || inputSchema.isBlank() ? JSON.createObjectNode() : JSON.readTree(inputSchema);
        if (!(parsed instanceof ObjectNode schema)) {
            throw new IllegalArgumentException("A tool input schema must be a JSON object");
        }
        if (schema.has("oneOf") || schema.has("allOf") || schema.has("anyOf")) {
            schema.remove(List.of("oneOf", "allOf", "anyOf"));
            if (!schema.has("type")) {
                schema.put("type", "object");
            }
        }
        if (!schema.has("type")) {
            schema.put("type", "object");
        }
        if ("object".equals(schema.path("type").asString("")) && !(schema.get("properties") instanceof ObjectNode)) {
            schema.putObject("properties");
        }
        return schema;
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package ai.javaclaw.providers.claudecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the assistant message from the upstream Messages SSE stream as it passes through the
 * relay: text, signed thinking, tool arguments, usage and stop reason are captured before Claude
 * Code's own recovery could replace them.
 */
final class SseCapture {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final Map<Integer, StringBuilder> arguments = new HashMap<>();
    private final List<Integer> unparsed = new ArrayList<>();
    private ObjectNode message;
    private boolean complete;

    synchronized void feed(byte[] chunk, int length) {
        pending.write(chunk, 0, length);
        byte[] buffer = pending.toByteArray();
        int start = 0;
        int[] boundary;
        // Event separators are ASCII, so splitting the raw bytes never cuts a UTF-8 sequence.
        while ((boundary = nextBoundary(buffer, start)) != null) {
            event(new String(buffer, start, boundary[0] - start, StandardCharsets.UTF_8));
            start = boundary[1];
        }
        pending.reset();
        pending.write(buffer, start, buffer.length - start);
    }

    synchronized ObjectNode message() {
        return message;
    }

    synchronized boolean complete() {
        return complete;
    }

    private void event(String frame) {
        List<String> data = new ArrayList<>();
        for (String line : frame.split("\r\n|\r|\n")) {
            if (line.startsWith("data:")) {
                data.add(line.substring(5).stripLeading());
            }
        }
        if (data.isEmpty() || String.join("", data).isEmpty()) {
            return;
        }
        JsonNode event = JSON.readTree(String.join("\n", data));
        switch (event.path("type").asString("")) {
            case "message_start" -> message = (ObjectNode) event.get("message").deepCopy();
            case "content_block_start" -> ((ArrayNode) message.get("content")).add(event.get("content_block").deepCopy());
            case "content_block_delta" -> blockDelta(event);
            case "content_block_stop" -> blockStop(event.get("index").intValue());
            case "message_delta" -> {
                if (event.get("delta") instanceof ObjectNode delta) {
                    message.setAll(delta);
                }
                if (event.get("usage") instanceof ObjectNode usage) {
                    ((ObjectNode) message.get("usage")).setAll(usage);
                }
            }
            case "message_stop" -> {
                ArrayNode content = (ArrayNode) message.get("content");
                unparsed.stream().sorted(Comparator.reverseOrder()).forEach(index -> content.remove(index.intValue()));
                complete = message.path("stop_reason").isString() && !message.path("stop_reason").stringValue().isEmpty()
                        && arguments.isEmpty();
            }
            default -> {
                // ping, error and future event types carry nothing to keep.
            }
        }
    }

    private void blockDelta(JsonNode event) {
        int index = event.get("index").intValue();
        JsonNode delta = event.get("delta");
        ObjectNode block = (ObjectNode) message.get("content").get(index);
        String type = delta.path("type").asString("");
        String field = switch (type) {
            case "text_delta" -> "text";
            case "thinking_delta" -> "thinking";
            case "signature_delta" -> "signature";
            default -> null;
        };
        if (field != null) {
            block.put(field, block.path(field).asString("") + delta.get(field).stringValue());
        } else if (type.equals("input_json_delta")) {
            arguments.computeIfAbsent(index, i -> new StringBuilder()).append(delta.get("partial_json").stringValue());
        } else if (type.equals("citations_delta")) {
            JsonNode citations = block.get("citations");
            ArrayNode list = citations instanceof ArrayNode array ? array : block.putArray("citations");
            list.add(delta.get("citation").deepCopy());
        }
    }

    private void blockStop(int index) {
        StringBuilder raw = arguments.remove(index);
        if (raw == null) {
            return;
        }
        try {
            // A no-argument tool call streams one input_json_delta with an empty partial_json.
            JsonNode input = raw.toString().isBlank() ? JSON.createObjectNode() : JSON.readTree(raw.toString());
            ((ObjectNode) message.get("content").get(index)).set("input", input);
        } catch (RuntimeException e) {
            // Streamed tool input is not validated: a reply cut off at max_tokens ends mid-argument.
            // The call is dropped at message_stop and never run with guessed arguments.
            unparsed.add(index);
        }
    }

    /** {@code [end of frame, start of next frame]} for the next blank-line separator, or {@code null}. */
    private static int[] nextBoundary(byte[] buffer, int from) {
        for (int i = from; i < buffer.length; i++) {
            if (buffer[i] != '\n') {
                continue;
            }
            int end = i > from && buffer[i - 1] == '\r' ? i - 1 : i;
            int next = i + 1;
            if (next < buffer.length && buffer[next] == '\r') {
                next++;
            }
            if (next < buffer.length && buffer[next] == '\n') {
                return new int[]{end, next + 1};
            }
        }
        return null;
    }
}

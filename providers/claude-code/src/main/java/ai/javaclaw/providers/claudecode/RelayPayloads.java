package ai.javaclaw.providers.claudecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prompt-cache hygiene the relay applies to the one Messages request it forwards (a port of the
 * Hermes DirectSDK admission transforms). Claude Code attaches per-request context (today's date,
 * the account reminder) in places the next per-call request does not replay, which would move
 * the cached prefix on every tool round. Every transform fails open: a payload it cannot prove
 * safe to change is forwarded exactly as Claude Code built it.
 */
final class RelayPayloads {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> UNCACHEABLE = Set.of("thinking", "redacted_thinking");
    private static final Set<String> PLAIN_TEXT_KEYS = Set.of("type", "text", "cache_control");
    // Anthropic's cache lookup walks back at most this many blocks from a breakpoint and allows
    // this many breakpoints across tools, system and messages.
    private static final int LOOKBACK = 20;
    private static final int MAX_BREAKPOINTS = 4;
    private static final String CACHE_CONTROL = "cache_control";
    // The only trailing system text moved back into the environment message: native's date line.
    private static final Pattern DATE_LINE = Pattern.compile("Today's date is \\d{4}-\\d{2}-\\d{2}\\.");

    /** The native request could not be reconciled with the tool results JavaClaw queried. */
    static final class QueriedTurnMismatch extends RuntimeException {
        QueriedTurnMismatch() {
            super("Native request does not uniquely match JavaClaw tool results");
        }
    }

    private RelayPayloads() {
    }

    /** The full relay pipeline; {@code unrestored} receives the reason a tool-result frame was left as native built it. */
    static byte[] prepare(byte[] payload, ArrayNode queried, StringBuilder unrestored) {
        try {
            payload = restoreQueriedTurn(payload, queried);
        } catch (QueriedTurnMismatch e) {
            // Restoration is hygiene, not a gate: an uncached tail beats a failed turn.
            unrestored.append(e.getMessage());
        }
        payload = stableRequestContext(payload, queried);
        payload = pinMessageBreakpoint(payload, queried);
        return streamToolInput(payload);
    }

    /**
     * Native enables fine-grained tool streaming only for a first-party base URL, so behind the relay
     * a large tool argument would arrive in one burst after minutes of silence.
     */
    static byte[] streamToolInput(byte[] payload) {
        try {
            JsonNode body = JSON.readTree(payload);
            boolean changed = false;
            for (JsonNode tool : body.path("tools")) {
                if (tool instanceof ObjectNode object && !object.has("eager_input_streaming")) {
                    object.put("eager_input_streaming", true);
                    changed = true;
                }
            }
            return changed ? write(body) : payload;
        } catch (RuntimeException e) {
            return payload;
        }
    }

    /**
     * Native splits today's date out of the environment message into a trailing system message and
     * prepends reminders to the newest user frame; neither recurs when the next request replays the
     * history. Moves the date line back to the end of the environment message and drops plain text
     * before the queried frame on later user turns.
     */
    static byte[] stableRequestContext(byte[] payload, ArrayNode queried) {
        try {
            JsonNode body = JSON.readTree(payload);
            if (!(body.get("messages") instanceof ArrayNode messages)) {
                return payload;
            }
            for (JsonNode message : messages) {
                if (!(message instanceof ObjectNode) || !message.has("content")) {
                    return payload;
                }
                JsonNode content = message.get("content");
                String role = message.path("role").asString("");
                if (!Set.of("system", "user", "assistant").contains(role) || !(content.isString() || content.isArray())) {
                    return payload;
                }
                for (JsonNode block : content.isArray() ? content : List.<JsonNode>of()) {
                    if (!(block instanceof ObjectNode) || !block.path("type").isString()
                            || (type(block).equals("text") && !block.path("text").isString())) {
                        return payload;
                    }
                }
            }
            if (queried != null) {
                for (JsonNode block : queried) {
                    if (!(block instanceof ObjectNode)) {
                        return payload;
                    }
                }
            }
            boolean changed = false;
            JsonNode last = messages.isEmpty() ? JSON.createObjectNode() : messages.get(messages.size() - 1);
            ObjectNode env = null;
            for (int i = 0; i < messages.size() - 1; i++) {
                if (role(messages.get(i)).equals("system")) {
                    env = (ObjectNode) messages.get(i);
                    break;
                }
            }
            JsonNode lastContent = last.get("content");
            JsonNode tail = lastContent != null && lastContent.isString() ? textBlock(lastContent.stringValue())
                    : lastContent != null && lastContent.isArray() && lastContent.size() == 1 ? lastContent.get(0)
                    : JSON.createObjectNode();
            if (role(last).equals("system") && env != null && type(tail).equals("text")
                    && DATE_LINE.matcher(tail.path("text").asString("")).matches()) {
                JsonNode original = env.get("content");
                String dateLine = tail.get("text").stringValue();
                JsonNode target = original.isString() ? textBlock(original.stringValue())
                        : !original.isEmpty() ? original.get(original.size() - 1) : JSON.createObjectNode();
                if (type(target).equals("text") && !target.get("text").stringValue().endsWith(dateLine)) {
                    if (original.isString()) {
                        env.put("content", original.stringValue() + "\n\n" + dateLine);
                    } else {
                        ((ObjectNode) target).put("text", target.get("text").stringValue() + "\n\n" + dateLine);
                    }
                    JsonNode mark = tail.has(CACHE_CONTROL) ? tail.get(CACHE_CONTROL) : last.get(CACHE_CONTROL);
                    messages.remove(messages.size() - 1);
                    JsonNode previous = messages.get(messages.size() - 1).get("content");
                    if (mark != null && previous instanceof ArrayNode array && !array.isEmpty()) {
                        ((ObjectNode) array.get(array.size() - 1)).putIfAbsent(CACHE_CONTROL, mark);
                    }
                    changed = true;
                }
            }
            JsonNode newest = JSON.createObjectNode();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (role(messages.get(i)).equals("user")) {
                    newest = messages.get(i);
                    break;
                }
            }
            if (queried != null && !queried.isEmpty() && anyNotToolResult(queried)
                    && newest.get("content") instanceof ArrayNode blocks && anyRole(messages, "assistant")) {
                List<Integer> hits = occurrences(blocks, queried);
                int k = hits.size() == 1 ? hits.getFirst() : 0;
                if (k > 0 && allPlainText(blocks, k)) {
                    dropLeading(blocks, k);
                    changed = true;
                }
            }
            return changed ? write(body) : payload;
        } catch (RuntimeException e) {
            return payload;
        }
    }

    /**
     * Keeps the recurring message {@code cache_control} on content the next request replays unchanged:
     * everything through the last assistant message plus the leading blocks of the newest turn that
     * equal the queried frame. Marks native placed after that span are folded onto its last block.
     */
    static byte[] pinMessageBreakpoint(byte[] payload, ArrayNode queried) {
        if (queried == null || queried.isEmpty()) {
            return payload;
        }
        try {
            ObjectNode body = (ObjectNode) JSON.readTree(payload);
            ArrayNode messages = (ArrayNode) body.get("messages");
            boolean reordered = frameFirst(messages, queried);
            List<Position> blocks = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).get("content") instanceof ArrayNode content) {
                    for (int j = 0; j < content.size(); j++) {
                        blocks.add(new Position(i, j, content.get(j)));
                    }
                }
            }
            List<Position> marked = blocks.stream().filter(p -> p.block() instanceof ObjectNode && p.block().has(CACHE_CONTROL)).toList();
            if (marked.isEmpty() && !reordered) {
                return payload;
            }
            int last = lastIndexOfRole(messages, "assistant");
            List<Position> stable = new ArrayList<>(blocks.stream().filter(p -> p.message() <= last).toList());
            JsonNode newest = last + 1 < messages.size() ? messages.get(last + 1) : JSON.createObjectNode();
            if (role(newest).equals("user") && newest.get("content") instanceof ArrayNode content) {
                List<Position> prefix = new ArrayList<>();
                for (int j = 0; j < Math.min(content.size(), queried.size()); j++) {
                    if (!plain(content.get(j)).equals(plain(queried.get(j)))) {
                        break;
                    }
                    prefix.add(new Position(last + 1, j, content.get(j)));
                }
                JsonNode rest = prefix.size() < content.size() ? content.get(prefix.size()) : null;
                if (!(rest instanceof ObjectNode && type(rest).equals("tool_result"))) {
                    stable.addAll(prefix);
                }
            }
            Position target = null;
            for (int p = stable.size() - 1; p >= 0; p--) {
                if (stable.get(p).block() instanceof ObjectNode && !UNCACHEABLE.contains(type(stable.get(p).block()))) {
                    target = stable.get(p);
                    break;
                }
            }
            boolean folded = false;
            if (target != null) {
                Position anchor = target;
                List<Position> after = marked.stream().filter(p -> anchor.before(p)).toList();
                if (!after.isEmpty()) {
                    List<JsonNode> moved = after.stream().map(p -> ((ObjectNode) p.block()).remove(CACHE_CONTROL)).toList();
                    ((ObjectNode) target.block()).putIfAbsent(CACHE_CONTROL, moved.getFirst());
                    folded = true;
                }
            }
            boolean turn = target != null && keepTurnMark(body, messages, last, target);
            return reordered || folded || turn ? write(body) : payload;
        } catch (RuntimeException e) {
            return payload;
        }
    }

    /**
     * Sends JavaClaw's first user turn without the context native prepends to it on the opening request
     * (the account reminder), so the turn is sent exactly as every later request replays it.
     */
    private static boolean frameFirst(ArrayNode messages, ArrayNode queried) {
        if (anyRole(messages, "assistant")) {
            return false;
        }
        JsonNode first = JSON.createObjectNode();
        for (JsonNode message : messages) {
            if (role(message).equals("user")) {
                first = message;
                break;
            }
        }
        if (!(first.get("content") instanceof ArrayNode content) || queried.isEmpty() || content.size() <= queried.size()) {
            return false;
        }
        List<Integer> starts = occurrences(content, queried);
        if (starts.size() != 1 || starts.getFirst() == 0 || !allPlainText(content, starts.getFirst())) {
            return false;
        }
        dropLeading(content, starts.getFirst());
        return true;
    }

    /**
     * After a tool loop longer than the cache lookback, keeps one more breakpoint where the open turn's
     * thinking starts, so the next turn can still read the entry an earlier request of this turn wrote.
     */
    private static boolean keepTurnMark(ObjectNode body, ArrayNode messages, int last, Position target) {
        int opening = -1;
        for (int i = 0; i < last; i++) {
            if (opensTurn(messages.get(i))) {
                opening = i;
            }
        }
        if (opening < 0) {
            return false;
        }
        int thought = -1;
        for (int i = opening + 1; i <= last; i++) {
            if (role(messages.get(i)).equals("assistant") && messages.get(i).get("content") instanceof ArrayNode content
                    && stream(content).anyMatch(b -> b instanceof ObjectNode && UNCACHEABLE.contains(type(b)))) {
                thought = i;
                break;
            }
        }
        if (thought < 0) {
            return false;
        }
        int frame = opening;
        for (int i = thought - 1; i > opening; i--) {
            if (role(messages.get(i)).equals("user")) {
                frame = i;
                break;
            }
        }
        if (!(messages.get(frame).get("content") instanceof ArrayNode content)) {
            return false;
        }
        int j = -1;
        for (int k = content.size() - 1; k >= 0; k--) {
            if (content.get(k) instanceof ObjectNode && !UNCACHEABLE.contains(type(content.get(k)))) {
                j = k;
                break;
            }
        }
        if (j < 0 || content.get(j).has(CACHE_CONTROL) || !new Position(frame, j, null).before(target)) {
            return false;
        }
        int span = 0;
        for (int i = frame; i < target.message(); i++) {
            JsonNode messageContent = messages.get(i).get("content");
            span += messageContent instanceof ArrayNode array ? array.size() : 1;
        }
        if (span - j + target.index() + 1 <= LOOKBACK) {
            return false;
        }
        Position mark = new Position(frame, j, content.get(j));
        List<Position> later = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).get("content") instanceof ArrayNode blocks) {
                for (int k = 0; k < blocks.size(); k++) {
                    Position p = new Position(i, k, blocks.get(k));
                    if (p.block() instanceof ObjectNode && p.block().has(CACHE_CONTROL) && mark.before(p)) {
                        later.add(p);
                    }
                }
            }
        }
        if (later.isEmpty()) {
            return false;
        }
        if (breakpoints(body) < MAX_BREAKPOINTS) {
            ((ObjectNode) mark.block()).set(CACHE_CONTROL, later.getFirst().block().get(CACHE_CONTROL).deepCopy());
            return true;
        }
        Position spare = later.stream().filter(p -> p.before(target)).findFirst().orElse(null);
        if (spare == null || !target.block().has(CACHE_CONTROL)) {
            return false;
        }
        ((ObjectNode) mark.block()).set(CACHE_CONTROL, ((ObjectNode) spare.block()).remove(CACHE_CONTROL));
        return true;
    }

    /**
     * Restores the newest tool-result frame exactly as JavaClaw queried it when the native frame matches
     * it unambiguously, so a native reminder appended to the last result cannot masquerade as tool output.
     * Only trailing bare text is removable; anything else throws {@link QueriedTurnMismatch}.
     */
    static byte[] restoreQueriedTurn(byte[] payload, ArrayNode queried) {
        if (queried == null || queried.isEmpty() || stream(queried).noneMatch(b -> b instanceof ObjectNode && type(b).equals("tool_result"))) {
            // An ordinary user-only frame is outside restoration scope, byte for byte.
            return payload;
        }
        try {
            JsonNode body = JSON.readTree(payload);
            ArrayNode messages = (ArrayNode) body.get("messages");
            int lastAssistant = lastIndexOfRole(messages, "assistant");
            JsonNode newest = lastAssistant + 1 < messages.size() ? messages.get(lastAssistant + 1) : JSON.createObjectNode();
            if (!role(newest).equals("user") || !(newest.get("content") instanceof ArrayNode nativeContent)) {
                throw new QueriedTurnMismatch();
            }
            List<String> resultIds = new ArrayList<>();
            for (JsonNode block : queried) {
                if (block instanceof ObjectNode && type(block).equals("tool_result")) {
                    JsonNode id = block.get("tool_use_id");
                    if (id == null || !id.isString() || id.stringValue().isEmpty()) {
                        throw new QueriedTurnMismatch();
                    }
                    resultIds.add(id.stringValue());
                }
            }
            if (new HashSet<>(resultIds).size() != resultIds.size()) {
                throw new QueriedTurnMismatch();
            }
            List<JsonNode> nativeIds = new ArrayList<>();
            for (JsonNode block : nativeContent) {
                if (block instanceof ObjectNode && type(block).equals("tool_result")) {
                    nativeIds.add(block.get("tool_use_id"));
                }
            }
            if (!resultIds.equals(nativeIds.stream().map(id -> id != null && id.isString() ? id.stringValue() : null).toList())) {
                throw new QueriedTurnMismatch();
            }
            ArrayNode restored = restoreSequence(nativeContent, queried);
            if (nativeContent.equals(restored)) {
                return payload;
            }
            ((ObjectNode) newest).set("content", restored);
            return write(body);
        } catch (QueriedTurnMismatch e) {
            throw e;
        } catch (RuntimeException e) {
            throw new QueriedTurnMismatch();
        }
    }

    private static ArrayNode restoreSequence(ArrayNode sent, ArrayNode host) {
        ArrayNode restored = JSON.createArrayNode();
        int i = 0;
        int j = 0;
        while (i < host.size()) {
            if (j >= sent.size()) {
                throw new QueriedTurnMismatch();
            }
            if (bareText(host.get(i)) && bareText(sent.get(j))) {
                int hostEnd = i + 1;
                int sentEnd = j + 1;
                while (hostEnd < host.size() && bareText(host.get(hostEnd))) {
                    hostEnd++;
                }
                while (sentEnd < sent.size() && bareText(sent.get(sentEnd))) {
                    sentEnd++;
                }
                String original = joinText(host, i, hostEnd);
                String actual = joinText(sent, j, sentEnd);
                // Extensions before a protected host block are insertions, not suffixes.
                if (!(hostEnd == host.size() ? actual.startsWith(original) : actual.equals(original))) {
                    throw new QueriedTurnMismatch();
                }
                for (int k = i; k < hostEnd; k++) {
                    restored.add(host.get(k).deepCopy());
                }
                i = hostEnd;
                j = sentEnd;
            } else {
                restored.add(restoreBlock(sent.get(j), host.get(i)));
                i++;
                j++;
            }
        }
        for (int k = j; k < sent.size(); k++) {
            if (!bareText(sent.get(k))) {
                throw new QueriedTurnMismatch();
            }
        }
        return restored;
    }

    private static JsonNode restoreBlock(JsonNode sent, JsonNode host) {
        if (!(sent instanceof ObjectNode sentBlock) || !(host instanceof ObjectNode hostBlock)) {
            throw new QueriedTurnMismatch();
        }
        ObjectNode restored = hostBlock.deepCopy();
        if (type(hostBlock).equals("tool_result")) {
            if (!Objects.equals(isError(sentBlock), isError(hostBlock)) || isError(sentBlock) == null) {
                throw new QueriedTurnMismatch();
            }
            Set<String> ignored = Set.of("content", "is_error", CACHE_CONTROL);
            if (!sentBlock.deepCopy().without(ignored).equals(hostBlock.deepCopy().without(ignored))
                    || sentBlock.has("content") != hostBlock.has("content")) {
                throw new QueriedTurnMismatch();
            }
            if (hostBlock.has("content")) {
                restored.set("content", restoreContent(sentBlock.get("content"), hostBlock.get("content")));
            }
        } else if (!plain(sentBlock).equals(plain(hostBlock))) {
            throw new QueriedTurnMismatch();
        }
        if (sentBlock.has(CACHE_CONTROL)) {
            if (hostBlock.has(CACHE_CONTROL) && !sentBlock.get(CACHE_CONTROL).equals(hostBlock.get(CACHE_CONTROL))) {
                throw new QueriedTurnMismatch();
            }
            restored.set(CACHE_CONTROL, sentBlock.get(CACHE_CONTROL).deepCopy());
        }
        return restored;
    }

    private static JsonNode restoreContent(JsonNode sent, JsonNode host) {
        // Scalar/list equivalence is allowed only for text with no metadata or markers.
        if (host.isString() || sent.isString()) {
            String left = contentText(sent);
            String right = contentText(host);
            if (left == null || right == null || !left.startsWith(right)) {
                throw new QueriedTurnMismatch();
            }
            return host.deepCopy();
        }
        if (sent instanceof ArrayNode sentList && host instanceof ArrayNode hostList) {
            return restoreSequence(sentList, hostList);
        }
        if (!sent.equals(host)) {
            throw new QueriedTurnMismatch();
        }
        return host.deepCopy();
    }

    /** {@code is_error}, where missing means false and anything but a boolean is unusable. */
    private static Boolean isError(ObjectNode block) {
        JsonNode value = block.get("is_error");
        if (value == null) {
            return false;
        }
        return value.isBoolean() ? value.booleanValue() : null;
    }

    private static String contentText(JsonNode value) {
        if (value.isString()) {
            return value.stringValue();
        }
        if (value instanceof ArrayNode array && stream(array).allMatch(RelayPayloads::bareText)) {
            return joinText(array, 0, array.size());
        }
        return null;
    }

    /** Every {@code cache_control} in the body, nested ones (tool_result content) and a top-level one included. */
    private static int breakpoints(JsonNode value) {
        int count = 0;
        Deque<JsonNode> pending = new ArrayDeque<>(List.of(value));
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node instanceof ObjectNode object) {
                for (var property : object.properties()) {
                    if (property.getKey().equals(CACHE_CONTROL)) {
                        count++;
                    } else {
                        pending.push(property.getValue());
                    }
                }
            } else if (node instanceof ArrayNode array) {
                array.forEach(pending::push);
            }
        }
        return count;
    }

    /** A user message carrying anything but tool results: a new turn. */
    private static boolean opensTurn(JsonNode message) {
        JsonNode content = message.get("content");
        return role(message).equals("user") && (content != null && content.isString()
                || content != null && stream(content).anyMatch(b -> !(b instanceof ObjectNode) || !type(b).equals("tool_result")));
    }

    private static List<Integer> occurrences(ArrayNode blocks, ArrayNode frame) {
        List<JsonNode> want = stream(frame).map(RelayPayloads::plain).toList();
        List<Integer> hits = new ArrayList<>();
        for (int k = 0; k + frame.size() <= blocks.size(); k++) {
            boolean match = true;
            for (int n = 0; n < frame.size() && match; n++) {
                match = plain(blocks.get(k + n)).equals(want.get(n));
            }
            if (match) {
                hits.add(k);
            }
        }
        return hits;
    }

    private static boolean allPlainText(ArrayNode blocks, int count) {
        for (int k = 0; k < count; k++) {
            JsonNode block = blocks.get(k);
            if (!(block instanceof ObjectNode) || !type(block).equals("text") || !PLAIN_TEXT_KEYS.containsAll(block.propertyNames())) {
                return false;
            }
        }
        return true;
    }

    /** Removes the first {@code count} blocks; a cache marker on them moves to the new first block, never multiplying. */
    private static void dropLeading(ArrayNode blocks, int count) {
        JsonNode mark = null;
        for (int k = 0; k < count && mark == null; k++) {
            mark = blocks.get(k).get(CACHE_CONTROL);
        }
        for (int k = 0; k < count; k++) {
            blocks.remove(0);
        }
        if (mark != null) {
            ((ObjectNode) blocks.get(0)).putIfAbsent(CACHE_CONTROL, mark);
        }
    }

    private static boolean bareText(JsonNode block) {
        return block instanceof ObjectNode && Set.copyOf(block.propertyNames()).equals(Set.of("type", "text"))
                && type(block).equals("text") && block.get("text").isString();
    }

    private static String joinText(ArrayNode blocks, int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int k = from; k < to; k++) {
            text.append(blocks.get(k).get("text").stringValue());
        }
        return text.toString();
    }

    private static JsonNode plain(JsonNode block) {
        return block instanceof ObjectNode object ? object.deepCopy().without(CACHE_CONTROL) : block;
    }

    private static boolean anyNotToolResult(ArrayNode blocks) {
        return stream(blocks).anyMatch(b -> !type(b).equals("tool_result"));
    }

    private static boolean anyRole(ArrayNode messages, String role) {
        return stream(messages).anyMatch(m -> role(m).equals(role));
    }

    private static int lastIndexOfRole(ArrayNode messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (role(messages.get(i)).equals(role)) {
                return i;
            }
        }
        return -1;
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items.stream();
    }

    private static String role(JsonNode message) {
        return message.path("role").asString("");
    }

    private static String type(JsonNode block) {
        return block.path("type").isString() ? block.get("type").stringValue() : "";
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "text");
        block.set("text", StringNode.valueOf(text));
        return block;
    }

    private static byte[] write(JsonNode body) {
        return JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }

    /** A content block at {@code messages[message].content[index]}, ordered as Python tuples compare. */
    private record Position(int message, int index, JsonNode block) {
        boolean before(Position other) {
            return message < other.message || message == other.message && index < other.index;
        }
    }
}

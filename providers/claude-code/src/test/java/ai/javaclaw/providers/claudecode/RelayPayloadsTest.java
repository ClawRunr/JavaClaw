package ai.javaclaw.providers.claudecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cases ported from the Hermes DirectSDK admission tests (breakpoint, stable context, first turn, turn start). */
class RelayPayloadsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ObjectNode MARKER = JSON.createObjectNode().put("type", "ephemeral");
    private static final ObjectNode HOUR = JSON.createObjectNode().put("type", "ephemeral").put("ttl", "1h");
    private static final ObjectNode RESULT = toolResult("t1", "real output");
    private static final ObjectNode QUESTION = text("the real question");
    private static final String DATE = "Today's date is 2026-09-23.";

    // --- pinMessageBreakpoint: the mark lands on the last block JavaClaw itself sent ---

    @Test
    void reminderInsideTheToolResultMovesTheMarkToTheLastReplayedBlock() {
        byte[] raw = wire(assistantProbe(), user(toolResult("t1", "real output\n<system-reminder>userEmail</system-reminder>")),
                message("system", marked(text(DATE))));

        assertPinned(raw, list(RESULT), List.of(new int[]{0, 1}));
    }

    @Test
    void annotationAfterJavaClawContentMovesTheMarkOntoTheQuestion() {
        assertPinned(wire(assistantProbe(), user(QUESTION, marked(text("Session context: v9 build")))), list(QUESTION),
                List.of(new int[]{1, 0}));
    }

    @Test
    void annotationPrependedToTheNewestTurnPinsTheAssistant() {
        assertPinned(wire(assistantProbe(), user(text("Any new preamble"), marked(QUESTION))), list(QUESTION),
                List.of(new int[]{0, 1}));
    }

    @Test
    void unchangedToolResultsKeepTheMarkWhenNativeAddsAMessageAfterThem() {
        assertPinned(wire(assistantProbe(), user(RESULT), message("system", marked(text(DATE)))), list(RESULT),
                List.of(new int[]{1, 0}));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void parallelToolResultsDoNotPinInsideAPartlyChangedUserMessage(int count) {
        ObjectNode assistant = message("assistant");
        List<ObjectNode> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ((ArrayNode) assistant.get("content")).addObject().put("type", "tool_use").put("id", "t" + i).put("name", "probe_" + i).putObject("input");
            results.add(toolResult("t" + i, "output " + i));
        }
        ObjectNode turn = message("user");
        results.subList(0, count - 1).forEach(((ArrayNode) turn.get("content"))::add);
        ((ArrayNode) turn.get("content")).add(marked(toolResult("t" + (count - 1), "output " + (count - 1) + "\n<system-reminder>native note</system-reminder>")));

        assertPinned(wire(assistant, turn), list(results.toArray(ObjectNode[]::new)), List.of(new int[]{0, count - 1}));
    }

    @Test
    void stableBreakpointOrNothingToAnchorForwardsTheSameBytes() {
        byte[] stable = wire(assistantProbe(), user(RESULT, marked(QUESTION)));
        byte[] opening = wire(user(marked(text("Any preamble"))));
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        byte[] unqueried = wire(assistantProbe(), user(marked(RESULT)));

        assertThat(RelayPayloads.pinMessageBreakpoint(stable, list(RESULT, QUESTION))).isSameAs(stable);
        assertThat(RelayPayloads.pinMessageBreakpoint(opening, list(QUESTION))).isSameAs(opening);
        assertThat(RelayPayloads.pinMessageBreakpoint(notJson, list(QUESTION))).isSameAs(notJson);
        assertThat(RelayPayloads.pinMessageBreakpoint(unqueried, null)).isSameAs(unqueried);
    }

    // --- frameFirst: the opening request sends the first turn as later requests replay it ---

    @Test
    void firstTurnDropsNativeContextAndKeepsOneMarker() {
        ObjectNode reminder = text("<system-reminder>\nuserEmail a@example.invalid\n</system-reminder>\n");
        ObjectNode frame = text("Use the terminal tool to run `echo alpha`.");
        byte[] raw = wire(user(reminder, frame), message("system", marked(text("Platform: darwin\n\n" + DATE), HOUR)));

        JsonNode out = read(RelayPayloads.pinMessageBreakpoint(raw, list(frame)));

        assertThat(out.get("messages").get(0).get("content")).isEqualTo(list(marked(frame, HOUR)));
        assertThat(marks(out)).hasSize(1);
    }

    @Test
    void firstTurnIsLeftAloneWhenTheFrameIsAmbiguous() {
        ObjectNode frame = text("question");
        byte[] raw = wire(user(text("reminder"), frame, marked(frame)));

        JsonNode out = read(RelayPayloads.pinMessageBreakpoint(raw, list(frame)));

        assertThat(plain(out.get("messages"))).isEqualTo(plain(read(raw).get("messages")));
    }

    // --- restoreQueriedTurn ---

    @Test
    void nativeEmailAnnotationIsNotForwardedAsToolOutput() {
        ObjectNode date = message("system", text("Today's date is 2026-09-30."));
        byte[] raw = wire(assistantProbe(), user(toolResult("t1", "real output\n<system-reminder>userEmail: m@example.com</system-reminder>")), date);

        JsonNode out = read(RelayPayloads.restoreQueriedTurn(raw, list(RESULT)));

        assertThat(out.get("messages").get(1).get("content")).isEqualTo(list(RESULT));
        assertThat(out.get("messages").get(2)).isEqualTo(date);
    }

    @Test
    void originalReminderInsideAToolResultIsPreserved() {
        ObjectNode original = toolResult("t1", "real output\n<system-reminder>quoted fixture</system-reminder>");
        ObjectNode sent = toolResult("t1", original.get("content").stringValue() + "\n<system-reminder>userEmail</system-reminder>");

        JsonNode out = read(RelayPayloads.restoreQueriedTurn(wire(assistantProbe(), user(sent)), list(original)));

        assertThat(out.get("messages").get(1).get("content")).isEqualTo(list(original));
    }

    @Test
    void parallelResultsAndATrailingNativeBlockRestoreTheQueriedFrame() {
        ObjectNode second = toolResult("t2", "second output");
        byte[] raw = wire(assistantProbe(), user(toolResult("t1", "real output\n<system-reminder>userEmail</system-reminder>"),
                second, text("native session context")));

        JsonNode out = read(RelayPayloads.restoreQueriedTurn(raw, list(RESULT, second)));

        assertThat(out.get("messages").get(1).get("content")).isEqualTo(list(RESULT, second));
    }

    @Test
    void unmatchedOrAmbiguousToolResultsFailClosed() {
        byte[] unmatched = wire(assistantProbe(), user(toolResult("different", "real output")));
        byte[] ambiguous = wire(assistantProbe(), user(RESULT, RESULT));

        assertThatThrownBy(() -> RelayPayloads.restoreQueriedTurn(unmatched, list(RESULT))).isInstanceOf(RelayPayloads.QueriedTurnMismatch.class);
        assertThatThrownBy(() -> RelayPayloads.restoreQueriedTurn(ambiguous, list(RESULT))).isInstanceOf(RelayPayloads.QueriedTurnMismatch.class);
    }

    @Test
    void anUnmatchedPlainUserTurnKeepsTheNativePayload() {
        byte[] raw = wire(assistantProbe(), user(text("rewritten")));

        assertThat(RelayPayloads.restoreQueriedTurn(raw, list(QUESTION))).isSameAs(raw);
    }

    @Test
    void prepareForwardsAnUnmatchedToolResultUnchangedAndReportsWhy() {
        byte[] raw = wire(assistantProbe(), user(toolResult("different", "real output")));
        StringBuilder unrestored = new StringBuilder();

        byte[] out = RelayPayloads.prepare(raw, list(RESULT), unrestored);

        assertThat(plain(read(out))).isEqualTo(plain(read(raw)));
        assertThat(unrestored.toString()).isEqualTo("Native request does not uniquely match JavaClaw tool results");
    }

    // --- stableRequestContext ---

    private static final String ENV = "Fixture environment";
    private static final String NEXT_DATE = "Today's date is 2031-01-01.";
    private static final ObjectNode FRAME = text("Next user question");
    private static final ObjectNode REMINDER = text("Native context reminder");

    private static ObjectNode thinkingAnswer() {
        ObjectNode assistant = message("assistant");
        ((ArrayNode) assistant.get("content")).addObject().put("type", "thinking").put("thinking", "Fixture reasoning").put("signature", "fixture-signature");
        ((ArrayNode) assistant.get("content")).add(text("Previous answer"));
        return assistant;
    }

    @Test
    void dateReturnsToAStringEnvironment() {
        JsonNode out = read(RelayPayloads.stableRequestContext(
                wire(message("system", ENV), thinkingAnswer(), user(REMINDER, FRAME), message("system", NEXT_DATE)), list(FRAME))).get("messages");

        assertThat(out).hasSize(3);
        assertThat(out.get(0).get("content").stringValue()).isEqualTo(ENV + "\n\n" + NEXT_DATE);
        assertThat(out.get(1)).isEqualTo(thinkingAnswer());
        assertThat(out.get(2).get("content")).isEqualTo(list(FRAME));
    }

    @Test
    void dateReturnsToAListEnvironment() {
        JsonNode out = read(RelayPayloads.stableRequestContext(
                wire(message("system", text("Earlier"), text(ENV)), thinkingAnswer(), user(REMINDER, FRAME), message("system", text(NEXT_DATE))),
                list(FRAME))).get("messages");

        assertThat(out.get(0).get("content")).isEqualTo(list(text("Earlier"), text(ENV + "\n\n" + NEXT_DATE)));
        assertThat(out.get(2).get("content")).isEqualTo(list(FRAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Today's date is 2031-01-01. Now 09:30.", "Per-request note", "Today's date is soon."})
    void trailingTextOtherThanTheDateLineStaysInPlace(String tail) {
        JsonNode out = read(RelayPayloads.stableRequestContext(
                wire(message("system", ENV), thinkingAnswer(), user(REMINDER, FRAME), message("system", tail)), list(FRAME))).get("messages");

        assertThat(out.get(0).get("content").stringValue()).isEqualTo(ENV);
        assertThat(out.get(out.size() - 1)).isEqualTo(message("system", tail));
        assertThat(out.get(out.size() - 2).get("content")).isEqualTo(list(FRAME));
    }

    @Test
    void removedMarkersTransferWithoutMultiplying() {
        ObjectNode turn = user(marked(REMINDER, HOUR), FRAME);
        JsonNode out = read(RelayPayloads.stableRequestContext(
                wire(message("system", ENV), thinkingAnswer(), turn, message("system", marked(text(NEXT_DATE), HOUR))), list(FRAME))).get("messages");

        assertThat(out.get(out.size() - 1).get("content")).isEqualTo(list(marked(FRAME, HOUR)));
        assertThat(marks(read(wire(out)))).hasSize(1);
    }

    @Test
    void ineligibleOrUnparseablePayloadsAreByteUnchanged() {
        byte[] toolResultTurn = wire(message("system", ENV), thinkingAnswer(), user(RESULT));
        byte[] opening = wire(message("system", ENV), user(REMINDER, FRAME));
        byte[] ambiguous = wire(message("system", ENV), thinkingAnswer(), user(FRAME, FRAME));
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);

        assertThat(RelayPayloads.stableRequestContext(toolResultTurn, list(RESULT))).isSameAs(toolResultTurn);
        assertThat(RelayPayloads.stableRequestContext(opening, list(FRAME))).isSameAs(opening);
        assertThat(RelayPayloads.stableRequestContext(ambiguous, list(FRAME))).isSameAs(ambiguous);
        assertThat(RelayPayloads.stableRequestContext(garbage, list(FRAME))).isSameAs(garbage);
    }

    @Test
    void stableContextIsIdempotent() {
        byte[] once = RelayPayloads.stableRequestContext(
                wire(message("system", ENV), thinkingAnswer(), user(REMINDER, FRAME), message("system", NEXT_DATE)), list(FRAME));

        assertThat(RelayPayloads.stableRequestContext(once, list(FRAME))).isSameAs(once);
    }

    // --- keepTurnMark: after a long tool loop the next turn still reads the conversation before it ---

    private static final ObjectNode OPENING = text("Run the echo steps one at a time.");
    private static final ObjectNode FOLLOW_UP = text("One more echo.");

    @Test
    void longLoopWithThinkingKeepsAMarkOnTheOpeningFrame() {
        JsonNode body = read(RelayPayloads.pinMessageBreakpoint(write(nativeRequest(longTurn(n -> true), false)), list(FOLLOW_UP)));

        assertThat(messageMarks(body)).containsExactly("0,0", "31,0");
    }

    @Test
    void atTheLimitANativeMarkMovesInsteadOfAFifth() {
        ObjectNode raw = nativeRequest(longTurn(n -> true), true);

        JsonNode body = read(RelayPayloads.pinMessageBreakpoint(write(raw), list(FOLLOW_UP)));

        assertThat(messageMarks(raw)).containsExactly("30,1", "32,0");
        assertThat(messageMarks(body)).containsExactly("0,0", "31,0");
        assertThat(marks(body)).hasSize(4);
        assertThat(plain(body)).isEqualTo(plain(raw));
    }

    @Test
    void noSpareSlotAndNoNativeMarkToMoveForwardsTheFoldOnly() {
        ObjectNode raw = nativeRequest(longTurn(n -> true), false);
        ((ObjectNode) raw.get("tools").get(0)).set("cache_control", HOUR);
        raw.set("cache_control", MARKER);

        JsonNode body = read(RelayPayloads.pinMessageBreakpoint(write(raw), list(FOLLOW_UP)));

        assertThat(messageMarks(body)).containsExactly("31,0");
    }

    @Test
    void turnMarkTakesTheTtlOfTheMarkAfterIt() {
        ObjectNode raw = nativeRequest(longTurn(n -> true), false);
        ArrayNode messages = (ArrayNode) raw.get("messages");
        ((ObjectNode) messages.get(messages.size() - 1).get("content").get(0)).set("cache_control", MARKER);

        JsonNode body = read(RelayPayloads.pinMessageBreakpoint(write(raw), list(FOLLOW_UP)));

        assertThat(body.get("messages").get(0).get("content").get(0).get("cache_control")).isEqualTo(MARKER);
        assertThat(body.get("messages").get(31).get("content").get(0).get("cache_control")).isEqualTo(MARKER);
    }

    @Test
    void shortLoopOrLoopWithoutThinkingKeepsOnlyTheRecurringMark() {
        List<ObjectNode> shortLoop = new ArrayList<>(turn(4, n -> true));
        shortLoop.add(user(FOLLOW_UP));

        assertThat(messageMarks(read(RelayPayloads.pinMessageBreakpoint(write(nativeRequest(shortLoop, false)), list(FOLLOW_UP)))))
                .containsExactly("11,0");
        assertThat(messageMarks(read(RelayPayloads.pinMessageBreakpoint(write(nativeRequest(longTurn(n -> false), false)), list(FOLLOW_UP)))))
                .containsExactly("31,0");
    }

    // --- streamToolInput ---

    @Test
    void asksForStreamedToolInputButKeepsAValueNativeSet() {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("tools").add(JSON.createObjectNode().put("name", "a")).add(JSON.createObjectNode().put("name", "b").put("eager_input_streaming", false));

        JsonNode out = read(RelayPayloads.streamToolInput(write(body)));

        assertThat(out.get("tools").get(0).get("eager_input_streaming").booleanValue()).isTrue();
        assertThat(out.get("tools").get(1).get("eager_input_streaming").booleanValue()).isFalse();
        byte[] noTools = write(JSON.createObjectNode().put("model", "m"));
        assertThat(RelayPayloads.streamToolInput(noTools)).isSameAs(noTools);
    }

    // --- fixtures ---

    /** JavaClaw's messages for one turn: the opening, {@code rounds} tool rounds and the answer. */
    private static List<ObjectNode> turn(int rounds, IntPredicate thinks) {
        List<ObjectNode> messages = new ArrayList<>(List.of(user(OPENING)));
        for (int n = 0; n < rounds; n++) {
            ObjectNode assistant = message("assistant");
            if (thinks.test(n)) {
                ((ArrayNode) assistant.get("content")).addObject().put("type", "thinking").put("thinking", "plan " + n).put("signature", "sig" + n);
            }
            ((ArrayNode) assistant.get("content")).addObject().put("type", "tool_use").put("id", "t" + n).put("name", "mcp__javaclaw__terminal")
                    .putObject("input").put("command", "echo " + n);
            messages.add(assistant);
            messages.add(user(toolResult("t" + n, String.valueOf(n))));
        }
        ObjectNode answer = message("assistant");
        if (thinks.test(rounds)) {
            ((ArrayNode) answer.get("content")).addObject().put("type", "thinking").put("thinking", "plan " + rounds).put("signature", "sig" + rounds);
        }
        ((ArrayNode) answer.get("content")).add(text("done"));
        messages.add(answer);
        return messages;
    }

    private static List<ObjectNode> longTurn(IntPredicate thinks) {
        List<ObjectNode> messages = new ArrayList<>(turn(14, thinks));
        messages.add(user(FOLLOW_UP));
        return messages;
    }

    /**
     * Claude Code's request layout: its environment message after the first turn, its per-request date
     * message last (carrying the message mark), two system marks, and for 2.1.287+ a tools mark and a
     * mark on the last assistant block.
     */
    private static ObjectNode nativeRequest(List<ObjectNode> messages, boolean claudeCode287) {
        ObjectNode body = JSON.createObjectNode().put("model", "claude-opus-5-5");
        ObjectNode tool = body.putArray("tools").addObject().put("name", "mcp__javaclaw__terminal").put("description", "run");
        tool.putObject("input_schema").put("type", "object");
        ArrayNode system = body.putArray("system");
        system.add(text("x-anthropic-billing-header: cc"));
        if (claudeCode287) {
            tool.set("cache_control", HOUR);
            system.add(marked(text("You are Claude Code. JavaClaw system prompt."), HOUR));
        } else {
            system.add(marked(text("You are Claude Code."), HOUR));
            system.add(marked(text("JavaClaw system prompt."), HOUR));
        }
        ArrayNode sent = body.putArray("messages");
        messages.forEach(m -> sent.add(m.deepCopy()));
        sent.insert(1, message("system", "Primary working directory: /tmp/javaclaw-claude-cwd"));
        if (claudeCode287 && sent.size() > 3) {
            ArrayNode last = (ArrayNode) sent.get(sent.size() - 2).get("content");
            ((ObjectNode) last.get(last.size() - 1)).set("cache_control", HOUR);
        }
        sent.add(message("system", marked(text("Today's date is 2026-10-10."), HOUR)));
        return body;
    }

    private static ObjectNode assistantProbe() {
        ObjectNode assistant = message("assistant");
        ((ArrayNode) assistant.get("content")).addObject().put("type", "thinking").put("thinking", "signed").put("signature", "sig");
        ((ArrayNode) assistant.get("content")).addObject().put("type", "tool_use").put("id", "t1").put("name", "mcp__javaclaw__probe").putObject("input");
        return assistant;
    }

    private static ObjectNode text(String text) {
        return JSON.createObjectNode().put("type", "text").put("text", text);
    }

    private static ObjectNode toolResult(String id, String content) {
        return JSON.createObjectNode().put("type", "tool_result").put("tool_use_id", id).put("content", content);
    }

    private static ObjectNode marked(ObjectNode block) {
        return marked(block, MARKER);
    }

    private static ObjectNode marked(ObjectNode block, ObjectNode marker) {
        ObjectNode copy = block.deepCopy();
        copy.set("cache_control", marker.deepCopy());
        return copy;
    }

    private static ObjectNode user(ObjectNode... blocks) {
        return message("user", blocks);
    }

    private static ObjectNode message(String role, ObjectNode... blocks) {
        ObjectNode message = JSON.createObjectNode().put("role", role);
        message.set("content", list(blocks));
        return message;
    }

    private static ObjectNode message(String role, String content) {
        return JSON.createObjectNode().put("role", role).put("content", content);
    }

    private static ArrayNode list(ObjectNode... blocks) {
        ArrayNode list = JSON.createArrayNode();
        for (ObjectNode block : blocks) {
            list.add(block.deepCopy());
        }
        return list;
    }

    private static byte[] wire(ObjectNode... messages) {
        ObjectNode body = JSON.createObjectNode().put("model", "m");
        ArrayNode list = body.putArray("messages");
        for (ObjectNode message : messages) {
            list.add(message.deepCopy());
        }
        return write(body);
    }

    private static byte[] wire(JsonNode messages) {
        ObjectNode body = JSON.createObjectNode().put("model", "m");
        body.set("messages", messages.deepCopy());
        return write(body);
    }

    private static byte[] write(JsonNode body) {
        return JSON.writeValueAsBytes(body);
    }

    private static JsonNode read(byte[] payload) {
        return JSON.readTree(payload);
    }

    private static void assertPinned(byte[] raw, ArrayNode queried, List<int[]> expected) {
        JsonNode out = read(RelayPayloads.pinMessageBreakpoint(raw, queried));
        assertThat(messageMarks(out)).containsExactlyElementsOf(expected.stream().map(p -> p[0] + "," + p[1]).toList());
        // Only the directive moves, never content.
        assertThat(plain(out)).isEqualTo(plain(read(raw)));
    }

    private static List<String> messageMarks(JsonNode body) {
        List<String> marks = new ArrayList<>();
        JsonNode messages = body.get("messages");
        for (int i = 0; i < messages.size(); i++) {
            JsonNode content = messages.get(i).get("content");
            for (int j = 0; content.isArray() && j < content.size(); j++) {
                if (content.get(j).has("cache_control")) {
                    marks.add(i + "," + j);
                }
            }
        }
        return marks;
    }

    /** Every cache_control in the body, as the API counts them toward its limit of four. */
    private static List<JsonNode> marks(JsonNode value) {
        List<JsonNode> found = new ArrayList<>();
        if (value instanceof ObjectNode object) {
            for (var property : object.properties()) {
                if (property.getKey().equals("cache_control")) {
                    found.add(property.getValue());
                } else {
                    found.addAll(marks(property.getValue()));
                }
            }
        } else if (value instanceof ArrayNode array) {
            array.forEach(item -> found.addAll(marks(item)));
        }
        return found;
    }

    private static JsonNode plain(JsonNode value) {
        if (value instanceof ObjectNode object) {
            ObjectNode copy = JSON.createObjectNode();
            object.properties().stream().filter(p -> !p.getKey().equals("cache_control")).forEach(p -> copy.set(p.getKey(), plain(p.getValue())));
            return copy;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = JSON.createArrayNode();
            array.forEach(item -> copy.add(plain(item)));
            return copy;
        }
        return value;
    }
}

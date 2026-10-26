package ai.javaclaw.providers.claudecode;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCodeRequestTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolCallingManager TOOLS = ToolCallingManager.builder().build();
    private static final String LONG_NAME = "a_tool_name_that_is_much_longer_than_fifty_characters_in_total";

    @Test
    void buildsTheConversationAsStreamJsonFrames() {
        ClaudeCodeRequest request = request(new Prompt(List.of(
                new SystemMessage("You are JavaClaw."),
                new SystemMessage("Environment info."),
                new UserMessage("Read it"),
                assistantCalling("read_file"),
                toolResults("first", "second"))));

        assertThat(request.system).isEqualTo("You are JavaClaw.\n\nEnvironment info.");
        assertThat(request.frames).hasSize(3);
        JsonNode replayedCall = request.frames.get(1).get("message").get("content").get(1);
        assertThat(replayedCall.get("name").stringValue()).isEqualTo("read_file");
        // Both tool results land in one user frame, which is the frame Claude Code answers.
        assertThat(request.queriedContent()).hasSize(2);
        assertThat(request.queriedContent().get(1).get("tool_use_id").stringValue()).isEqualTo("call_2");
    }

    @Test
    void replaysAnUnchangedTurnAsClaudeCodeProducedIt() {
        AssistantMessage turn = withNativeTurn(assistantCalling("read_file"), "Checking.");

        ClaudeCodeRequest request = request(new Prompt(List.of(new UserMessage("Read it"), turn, toolResults("contents"))));

        JsonNode replayed = request.frames.get(1).get("message");
        assertThat(replayed.get("id").stringValue()).isEqualTo("msg_native");
        assertThat(replayed.has("usage")).isFalse();
        assertThat(replayed.get("content").get(0).get("signature").stringValue()).isEqualTo("signed");
    }

    @Test
    void replaysAnEditedTurnWithoutStaleSignedThinking() {
        AssistantMessage turn = withNativeTurn(assistantCalling("read_file"), "Something else entirely.");

        ClaudeCodeRequest request = request(new Prompt(List.of(new UserMessage("Read it"), turn, toolResults("contents"))));

        JsonNode content = request.frames.get(1).get("message").get("content");
        assertThat(content).noneMatch(block -> block.get("type").stringValue().equals("thinking"));
        assertThat(content.get(0).get("text").stringValue()).isEqualTo("Checking.");
    }

    @Test
    void aliasesLongToolNamesAndPrefixesKnownToolsOnReplay() {
        ClaudeCodeRequest request = request(new Prompt(List.of(new UserMessage("Go"), assistantCalling(LONG_NAME), toolResults("done")),
                ToolCallingChatOptions.builder().toolCallbacks(tool(LONG_NAME)).build()));

        String nativeName = request.nativeNames.get(LONG_NAME);
        assertThat(nativeName).hasSize(50).startsWith(LONG_NAME.substring(0, 41) + "_");
        assertThat(request.javaclawNames).containsEntry(nativeName, LONG_NAME);
        assertThat(request.manifest.get(0).get("name").stringValue()).isEqualTo(nativeName);
        assertThat(request.extraBody.get("tools").get(0).get("name").stringValue()).isEqualTo("mcp__javaclaw__" + nativeName);
        assertThat(request.frames.get(1).get("message").get("content").get(1).get("name").stringValue())
                .isEqualTo("mcp__javaclaw__" + nativeName);
    }

    @Test
    void sendsOutputLimitsButNeverSamplingControls() {
        ClaudeCodeRequest request = request(new Prompt(List.of(new UserMessage("Hi")),
                ToolCallingChatOptions.builder().model("opus").maxTokens(2400).temperature(0.2).topP(0.9).stopSequences(List.of("END")).build()));

        assertThat(request.nativeModel).isEqualTo("claude-opus-5-5[1m]");
        assertThat(request.extraBody.get("max_tokens").intValue()).isEqualTo(2400);
        assertThat(request.extraBody.get("stop_sequences").get(0).stringValue()).isEqualTo("END");
        assertThat(request.extraBody.has("temperature")).isFalse();
        assertThat(request.extraBody.has("top_p")).isFalse();
    }

    @Test
    void dropsTopLevelSchemaCombinators() {
        JsonNode schema = ClaudeCodeRequest.normalizeInputSchema("{\"anyOf\":[{\"required\":[\"a\"]},{\"required\":[\"b\"]}]}");

        assertThat(schema.has("anyOf")).isFalse();
        assertThat(schema.get("type").stringValue()).isEqualTo("object");
        assertThat(schema.get("properties").isObject()).isTrue();
    }

    @Test
    void keepsOneSessionIdPerConversation() {
        String first = request(new Prompt(List.of(new SystemMessage("S"), new UserMessage("Hello")))).sessionId();
        String later = request(new Prompt(List.of(new SystemMessage("S"), new UserMessage("Hello"),
                new AssistantMessage("Hi"), new UserMessage("Again")))).sessionId();
        String other = request(new Prompt(List.of(new SystemMessage("S"), new UserMessage("Different")))).sessionId();

        assertThat(later).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(java.util.UUID.fromString(first).version()).isEqualTo(4);
    }

    @Test
    void refusesHistoryThatDoesNotEndWithTheUser() {
        assertThatThrownBy(() -> request(new Prompt(List.of(new UserMessage("Hi"), new AssistantMessage("Prefill")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ClaudeCodeRequest request(Prompt prompt) {
        return ClaudeCodeRequest.from(prompt, "sonnet", TOOLS);
    }

    private static AssistantMessage assistantCalling(String tool) {
        return AssistantMessage.builder()
                .content("Checking.")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", tool, "{\"path\":\"a.txt\"}")))
                .build();
    }

    /** The assistant message as Claude Code produced it, with the projection JavaClaw saw for {@code seenText}. */
    private static AssistantMessage withNativeTurn(AssistantMessage visible, String seenText) {
        String turn = """
                {"version":1,
                 "messages":[{"id":"msg_native","role":"assistant","usage":{"input_tokens":1},
                              "content":[{"type":"thinking","thinking":"t","signature":"signed"},
                                         {"type":"text","text":"%s"},
                                         {"type":"tool_use","id":"call_1","name":"mcp__javaclaw__read_file","input":{"path":"a.txt"}}]}],
                 "projection":{"content":"%s","tool_calls":[{"id":"call_1","name":"read_file","input":{"path":"a.txt"}}]}}
                """.formatted(seenText, seenText);
        return AssistantMessage.builder()
                .content(visible.getText())
                .toolCalls(visible.getToolCalls())
                .properties(Map.of(ClaudeCodeRequest.NATIVE_TURN, JSON.writeValueAsString(JSON.readTree(turn))))
                .build();
    }

    private static Message toolResults(String... outputs) {
        List<ToolResponseMessage.ToolResponse> responses = new java.util.ArrayList<>();
        for (int i = 0; i < outputs.length; i++) {
            responses.add(new ToolResponseMessage.ToolResponse("call_" + (i + 1), "read_file", outputs[i]));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }
}

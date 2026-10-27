package ai.javaclaw.providers.claudecode;

import ai.javaclaw.providers.claudecode.FakeAnthropicUpstream.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real Claude Code CLI against {@link FakeAnthropicUpstream}: no credentials and no
 * inference, but the actual CLI flags, stream-json protocol, replay acknowledgments, inert MCP
 * server and single-request admission. Skipped when {@code claude} is not installed.
 */
@EnabledIf("claudeInstalled")
class ClaudeCodeChatModelIntegrationTest {

    record ReadFile(String path) {
    }

    @TempDir
    Path home;

    private final List<String> toolInvocations = new CopyOnWriteArrayList<>();
    private ToolCallback readFile;

    static boolean claudeInstalled() {
        return ClaudeCodeCli.resolve(null, System.getenv()).isPresent();
    }

    @BeforeEach
    void setUp() {
        Function<ReadFile, String> reader = request -> {
            toolInvocations.add(request.path());
            return "fixture contents";
        };
        readFile = FunctionToolCallback.builder("read_file", reader)
                .description("Read a fixture file")
                .inputType(ReadFile.class)
                .build();
    }

    @Test
    void answersWithTextThroughExactlyOneUpstreamRequest() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.TEXT)) {
            ChatResponse response = model(upstream).call(new Prompt(new UserMessage("Local protocol fixture")));

            assertThat(response.getResult().getOutput().getText()).isEqualTo(FakeAnthropicUpstream.TEXT);
            assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
            assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(101 + 211 + 313);
            assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(37);
            assertThat(upstream.requests()).isEqualTo(1);
        }
    }

    @Test
    void returnsToolCallsUnderJavaClawNamesAndAdvertisesFullToolSchemas() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.TOOL)) {
            ChatResponse response = model(upstream).call(promptWithTools(new UserMessage("Read the fixture")));

            AssistantMessage output = response.getResult().getOutput();
            assertThat(output.getToolCalls()).singleElement().satisfies(call -> {
                assertThat(call.name()).isEqualTo("read_file");
                assertThat(call.id()).isEqualTo("toolu_fixture_1");
                assertThat(call.arguments()).isEqualTo("{\"path\":\"fixture.txt\"}");
            });
            assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("tool_calls");
            JsonNode tool = upstream.bodies.getFirst().get("tools").get(0);
            assertThat(tool.get("name").stringValue()).isEqualTo("mcp__javaclaw__read_file");
            assertThat(tool.get("description").stringValue()).isEqualTo("Read a fixture file");
            assertThat(tool.get("eager_input_streaming").booleanValue()).isTrue();
            assertThat(upstream.requests()).isEqualTo(1);
        }
    }

    @Test
    void replaysHistoryWithSignedThinkingAndToolResults() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.THINKING_TOOL, Mode.TEXT)) {
            ClaudeCodeChatModel model = model(upstream);
            UserMessage question = new UserMessage("Read the fixture");
            AssistantMessage toolTurn = model.call(promptWithTools(question)).getResult().getOutput();
            ToolResponseMessage result = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("toolu_fixture_1", "read_file", "fixture contents")))
                    .build();

            ChatResponse answer = model.call(promptWithTools(question, toolTurn, result));

            assertThat(answer.getResult().getOutput().getText()).isEqualTo(FakeAnthropicUpstream.TEXT);
            assertThat(upstream.requests()).isEqualTo(2);
            List<JsonNode> replayed = FakeAnthropicUpstream.blocks(upstream.bodies.get(1));
            assertThat(replayed).anySatisfy(block -> {
                assertThat(block.get("type").stringValue()).isEqualTo("thinking");
                assertThat(block.get("signature").stringValue()).isEqualTo("fixture-signature");
            });
            assertThat(replayed).anySatisfy(block -> {
                assertThat(block.get("type").stringValue()).isEqualTo("tool_use");
                assertThat(block.get("name").stringValue()).isEqualTo("mcp__javaclaw__read_file");
            });
            assertThat(replayed).anySatisfy(block -> {
                assertThat(block.get("type").stringValue()).isEqualTo("tool_result");
                assertThat(block.get("tool_use_id").stringValue()).isEqualTo("toolu_fixture_1");
                assertThat(block.get("content").toString()).contains("fixture contents");
            });
        }
    }

    @Test
    void javaClawsToolLoopExecutesTheToolAndFinishes() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.TOOL, Mode.TEXT)) {
            ChatClient client = ChatClient.builder(model(upstream))
                    .defaultAdvisors(ToolCallingAdvisor.builder().build())
                    .defaultTools(readFile)
                    .build();

            String answer = client.prompt("Read the fixture").call().content();

            assertThat(answer).isEqualTo(FakeAnthropicUpstream.TEXT);
            assertThat(toolInvocations).containsExactly("fixture.txt");
            assertThat(upstream.requests()).isEqualTo(2);
        }
    }

    @Test
    void streamsTextAndRunsTheToolLoop() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.TOOL, Mode.TEXT)) {
            ChatClient client = ChatClient.builder(model(upstream))
                    .defaultAdvisors(ToolCallingAdvisor.builder().build())
                    .defaultTools(readFile)
                    .build();

            List<String> tokens = client.prompt("Read the fixture").stream().content().collectList().block(Duration.ofSeconds(60));

            assertThat(String.join("", tokens)).endsWith(FakeAnthropicUpstream.TEXT);
            assertThat(toolInvocations).containsExactly("fixture.txt");
        }
    }

    @Test
    void mapsAnUpstreamRejectionToItsHttpStatus() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.RATE_LIMITED)) {
            assertThatThrownBy(() -> model(upstream).call(new Prompt(new UserMessage("Local protocol fixture"))))
                    .isInstanceOfSatisfying(ClaudeCodeException.class, e -> {
                        assertThat(e.statusCode()).isEqualTo(429);
                        assertThat(e.getMessage()).contains("fixture limit");
                    });
            assertThat(upstream.requests()).isEqualTo(1);
        }
    }

    @Test
    void reportsATruncatedReplyAsLength() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.MAX_TOKENS)) {
            ChatResponse response = model(upstream).call(new Prompt(new UserMessage("Local protocol fixture")));

            assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("length");
            assertThat(upstream.requests()).isEqualTo(1);
        }
    }

    @Test
    void cancellingAStreamClosesTheUpstreamConnection() throws Exception {
        try (FakeAnthropicUpstream upstream = new FakeAnthropicUpstream(Mode.HANG)) {
            Disposable subscription = model(upstream).stream(new Prompt(new UserMessage("Local protocol fixture"))).subscribe();
            assertThat(upstream.entered.await(30, TimeUnit.SECONDS)).isTrue();

            subscription.dispose();

            assertThat(upstream.disconnected.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Prompt promptWithTools(org.springframework.ai.chat.messages.Message... messages) {
        return new Prompt(List.of(messages), ToolCallingChatOptions.builder().toolCallbacks(readFile).build());
    }

    private ClaudeCodeChatModel model(FakeAnthropicUpstream upstream) {
        Map<String, String> env = Map.of(
                "PATH", "/usr/bin:/bin",
                "HOME", home.toString(),
                "XDG_CONFIG_HOME", home.toString(),
                "CLAUDE_CONFIG_DIR", home.toString(),
                "ANTHROPIC_API_KEY", "fixture-not-a-real-key",
                "ANTHROPIC_BASE_URL", upstream.url(),
                "DISABLE_TELEMETRY", "1",
                "DISABLE_ERROR_REPORTING", "1");
        return ClaudeCodeChatModel.builder()
                .command(ClaudeCodeCli.resolve(null, System.getenv()).orElseThrow())
                .environment(env)
                .model("claude-sonnet-4-6")
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}

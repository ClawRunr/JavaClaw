package ai.javaclaw.providers.claudecode;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalGate;
import ai.javaclaw.approval.ApprovalListener;
import ai.javaclaw.approval.ApprovalProperties;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.ApprovalTurn;
import ai.javaclaw.approval.PendingApprovals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Claude Code provider's tool calls go through the approval gate, checked without the real
 * {@code claude} CLI so it runs in CI ({@link ClaudeCodeChatModelIntegrationTest} covers the real CLI
 * where it is installed).
 *
 * <p>A fake {@code claude} script speaks just enough of the CLI's stream-json protocol: it
 * acknowledges replayed history, and answers the newest frame with a {@code read_file} tool call, or
 * with text once that frame carries a tool result. Each call's stdin is logged so the tests can see
 * what Claude Code was told about the tool. The provider itself — request building, process
 * handling, stream-json parsing, tool-name mapping — is the production code.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake CLI is a POSIX shell script")
class ClaudeCodeApprovalGateTest {

    static final String ANSWER = "Fixture answer";

    /** Replays history acks, then a tool call or (after a tool result) a text answer, like {@code claude -p --max-turns 1}. */
    private static final String FAKE_CLAUDE = """
            #!/bin/sh
            printf '%%s\\n' '#call' >> "$FAKE_CLAUDE_LOG"
            last=''
            while IFS= read -r line || [ -n "$line" ]; do
              printf '%%s\\n' "$line" >> "$FAKE_CLAUDE_LOG"
              case "$line" in
                *'"shouldQuery":false'*)
                  echo '{"type":"result","subtype":"success","is_error":false,"num_turns":0,"usage":{"input_tokens":0,"output_tokens":0}}' ;;
                *) last="$line" ;;
              esac
            done
            case "$last" in
              *tool_result*)
                echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"%1$s"}}}'
                echo '{"type":"stream_event","event":{"type":"message_stop"}}'
                echo '{"type":"assistant","message":{"id":"msg_text","role":"assistant","content":[{"type":"text","text":"%1$s"}],"stop_reason":"end_turn"}}'
                echo '{"type":"result","subtype":"success","is_error":false,"num_turns":1,"usage":{"input_tokens":3,"output_tokens":2}}'
                exit 0 ;;
              *)
                echo '{"type":"stream_event","event":{"type":"message_stop"}}'
                echo '{"type":"assistant","message":{"id":"msg_tool","role":"assistant","content":[{"type":"tool_use","id":"toolu_fake_1","name":"mcp__javaclaw__read_file","input":{"path":"fixture.txt"}}],"stop_reason":"tool_use"}}'
                echo '{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":1,"usage":{"input_tokens":3,"output_tokens":2}}'
                exit 1 ;;
            esac
            """.formatted(ANSWER);

    record ReadFile(String path) {
    }

    @TempDir
    Path dir;

    private final List<String> toolRuns = new CopyOnWriteArrayList<>();
    private Path fakeClaude;
    private Path stdinLog;
    private ToolCallback readFile;

    @BeforeEach
    void setUp() throws Exception {
        fakeClaude = dir.resolve("claude");
        Files.writeString(fakeClaude, FAKE_CLAUDE);
        Files.setPosixFilePermissions(fakeClaude, PosixFilePermissions.fromString("rwx------"));
        stdinLog = dir.resolve("stdin.log");
        Function<ReadFile, String> reader = request -> {
            toolRuns.add(request.path());
            return "fixture contents";
        };
        readFile = FunctionToolCallback.builder("read_file", reader)
                .description("Read a fixture file")
                .inputType(ReadFile.class)
                .build();
    }

    @Test
    void theFakeCliRunsTheToolWithoutAGate() throws Exception {
        ChatClient client = ChatClient.builder(model())
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .defaultTools(readFile)
                .build();

        assertThat(client.prompt("Read the fixture").call().content()).isEqualTo(ANSWER);
        // The fixture really drives the tool loop, so the gated tests below are meaningful
        assertThat(toolRuns).containsExactly("fixture.txt");
        assertThat(calls()).hasSize(2);
        assertThat(String.join("\n", calls().get(1))).contains("tool_result").contains("fixture contents");
    }

    @Test
    void aClaudeCodeToolCallIsRefusedWhenNobodyCanApproveIt() throws Exception {
        ChatClient client = gatedClient(new PendingApprovals());

        // No turn that can ask: read_file is unclassified, so it needs approval and is refused
        String answer = client.prompt("Read the fixture").call().content();

        assertThat(answer).isEqualTo(ANSWER);
        assertThat(toolRuns).isEmpty();
        assertThat(calls()).hasSize(2);
        assertThat(String.join("\n", calls().get(1)))
                .contains("toolu_fake_1").contains("The user did not approve this action.").doesNotContain("fixture contents");
    }

    @Test
    void aStreamedClaudeCodeToolCallWaitsForApprovalAndRunsOnceAllowed() throws Exception {
        PendingApprovals pending = new PendingApprovals();
        List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();
        ChatClient client = gatedClient(pending);

        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> String.join("",
                client.prompt("Read the fixture").toolContext(askingTurn(requests).toolContext())
                        .stream().content().collectList().block(Duration.ofSeconds(30))));

        await().atMost(30, TimeUnit.SECONDS).until(() -> !requests.isEmpty());
        ApprovalRequest request = requests.getFirst();
        assertThat(request.toolName()).isEqualTo("read_file");
        assertThat(request.toolCallId()).isEqualTo("toolu_fake_1");
        assertThat(request.arguments()).isEqualTo("{\"path\":\"fixture.txt\"}");
        assertThat(toolRuns).as("nothing runs before the owner answers").isEmpty();

        assertThat(pending.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.ALLOW_ONCE)).isTrue();

        assertThat(answer.get(30, TimeUnit.SECONDS)).endsWith(ANSWER);
        assertThat(toolRuns).containsExactly("fixture.txt");
        assertThat(String.join("\n", calls().get(1))).contains("fixture contents");
    }

    @Test
    void aStreamedClaudeCodeToolCallThatTheOwnerDeniesNeverRuns() throws Exception {
        PendingApprovals pending = new PendingApprovals();
        List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();
        ChatClient client = gatedClient(pending);

        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> String.join("",
                client.prompt("Read the fixture").toolContext(askingTurn(requests).toolContext())
                        .stream().content().collectList().block(Duration.ofSeconds(30))));

        await().atMost(30, TimeUnit.SECONDS).until(() -> !requests.isEmpty());
        ApprovalRequest request = requests.getFirst();
        assertThat(pending.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.DENY)).isTrue();

        assertThat(answer.get(30, TimeUnit.SECONDS)).endsWith(ANSWER);
        assertThat(toolRuns).isEmpty();
        assertThat(String.join("\n", calls().get(1))).contains("The user did not approve this action.");
    }

    private static ApprovalTurn askingTurn(List<ApprovalRequest> requests) {
        return new ApprovalTurn("web", _ -> true, new ApprovalListener() {
            @Override
            public void requested(ApprovalRequest request) {
                requests.add(request);
            }

            @Override
            public void resolved(ApprovalOutcome outcome) {
            }
        });
    }

    private ChatClient gatedClient(PendingApprovals pending) {
        ApprovalGate gate = new ApprovalGate(ApprovalProperties.defaults(), pending, Clock.systemUTC());
        return ChatClient.builder(model())
                .defaultAdvisors(ToolCallingAdvisor.builder()
                        .toolCallingManager(gate.toolCallingManager(ToolCallingManager.builder().build()))
                        .build())
                .defaultTools(readFile)
                .build();
    }

    private ClaudeCodeChatModel model() {
        return ClaudeCodeChatModel.builder()
                .command(fakeClaude.toString())
                .environment(Map.of(
                        "PATH", "/usr/bin:/bin",
                        "HOME", dir.toString(),
                        // Unreachable on purpose: the fake CLI never calls the API, and nothing else may
                        "ANTHROPIC_BASE_URL", "http://127.0.0.1:9",
                        "FAKE_CLAUDE_LOG", stdinLog.toString()))
                .model("claude-sonnet-4-6")
                .timeout(Duration.ofSeconds(20))
                .build();
    }

    /** The stdin of each fake CLI run, in order. */
    private List<List<String>> calls() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        for (String line : Files.readAllLines(stdinLog)) {
            if (line.equals("#call")) {
                calls.add(new ArrayList<>());
            } else {
                calls.getLast().add(line);
            }
        }
        return calls;
    }
}

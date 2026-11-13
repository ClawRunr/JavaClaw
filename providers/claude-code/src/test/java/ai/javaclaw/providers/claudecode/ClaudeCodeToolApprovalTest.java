package ai.javaclaw.providers.claudecode;

import ai.javaclaw.agent.Agent.DecisionResult;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.DefaultAgent;
import ai.javaclaw.agent.memory.FileSystemChatMemoryRepository;
import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalPolicy;
import ai.javaclaw.approval.ApprovalProperties;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Claude Code provider's tool calls pause for approval and resume, checked without the real
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
@SuppressWarnings("removal")
class ClaudeCodeToolApprovalTest {

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
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
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
    void theFakeCliRunsTheToolWithoutApproval() throws Exception {
        ChatClient client = ChatClient.builder(model())
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .defaultTools(readFile)
                .build();

        assertThat(client.prompt("Read the fixture").call().content()).isEqualTo(ANSWER);
        // The fixture really drives the tool loop, so the approval tests below are meaningful
        assertThat(toolRuns).containsExactly("fixture.txt");
        assertThat(calls()).hasSize(2);
        assertThat(String.join("\n", calls().get(1))).contains("tool_result").contains("fixture contents");
    }

    @Test
    void aClaudeCodeToolCallIsDeniedWhenNobodyCanApproveIt() throws Exception {
        // No one to ask: read_file is not in allow, so it is denied and the turn finishes
        String answer = agent().respondTo("task-1", "Read the fixture");

        assertThat(answer).isEqualTo(ANSWER);
        assertThat(toolRuns).isEmpty();
        assertThat(calls()).hasSize(2);
        assertThat(String.join("\n", calls().get(1)))
                .contains("toolu_fake_1").contains(Denial.NOBODY_TO_ASK.message()).doesNotContain("fixture contents");
    }

    @Test
    void aStreamedClaudeCodeToolCallPausesAndRunsOnceAllowed() throws Exception {
        DefaultAgent agent = agent();

        agent.respondTo("web", "Read the fixture", events::add, _ -> true);

        ApprovalRequest request = onlyRequest();
        assertThat(request.toolName()).isEqualTo("read_file");
        assertThat(request.toolCallId()).isEqualTo("toolu_fake_1");
        assertThat(request.arguments()).isEqualTo("{\"path\":\"fixture.txt\"}");
        assertThat(toolRuns).as("nothing runs before the owner answers").isEmpty();
        assertThat(calls()).hasSize(1);

        assertThat(agent.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.ALLOW_ONCE,
                _ -> true, events::add)).isEqualTo(DecisionResult.RESUMED);

        assertThat(events).contains(new AgentEvent.Token(ANSWER)).last().isEqualTo(new AgentEvent.Done());
        assertThat(toolRuns).containsExactly("fixture.txt");
        // The resumed request replays the question, Claude Code's tool call and the result
        assertThat(String.join("\n", calls().get(1)))
                .contains("Read the fixture").contains("toolu_fake_1").contains("fixture contents");
    }

    @Test
    void aStreamedClaudeCodeToolCallThatTheOwnerDeniesNeverRuns() throws Exception {
        DefaultAgent agent = agent();
        agent.respondTo("web", "Read the fixture", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();

        agent.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.DENY, _ -> true, events::add);

        assertThat(events).contains(new AgentEvent.Token(ANSWER));
        assertThat(toolRuns).isEmpty();
        assertThat(String.join("\n", calls().get(1))).contains(Denial.NOT_APPROVED.message());
    }

    private ApprovalRequest onlyRequest() {
        return events.stream()
                .filter(AgentEvent.ApprovalRequested.class::isInstance)
                .map(e -> ((AgentEvent.ApprovalRequested) e).request())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No approval request in " + events));
    }

    /** The agent as the application builds it: approval policy, tool loop, file-backed chat memory. */
    private DefaultAgent agent() throws Exception {
        ApprovalPolicy policy = new ApprovalPolicy(ApprovalProperties.defaults());
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new FileSystemChatMemoryRepository(new FileSystemResource(dir.toString() + "/")))
                .build();
        return new DefaultAgent(ChatClient.builder(model())
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(policy.toolCallingManager()).build(),
                        MessageChatMemoryAdvisor.builder(memory).build())
                .defaultTools(readFile)
                .build(), policy);
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

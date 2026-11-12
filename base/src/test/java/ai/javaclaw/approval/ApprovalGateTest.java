package ai.javaclaw.approval;

import ai.javaclaw.approval.ApprovalGateFixtures.RecordingTool;
import ai.javaclaw.approval.ApprovalListener.ApprovalOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate through a real {@code DefaultToolCallingManager}: tools here actually run (and record
 * that they did) unless the gate stops them.
 */
class ApprovalGateTest {

    static final String CONVERSATION = "web";

    final PendingApprovals pending = new PendingApprovals();
    final BlockingQueue<ApprovalRequest> requests = new LinkedBlockingQueue<>();
    final List<ApprovalOutcome> outcomes = new CopyOnWriteArrayList<>();
    final Map<String, RecordingTool> tools = new LinkedHashMap<>();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    volatile boolean clientConnected = true;
    ToolCallingManager manager = managerFor(ApprovalGateFixtures.DEFAULTS);

    final ApprovalListener listener = new ApprovalListener() {
        @Override
        public void requested(ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public void resolved(ApprovalOutcome outcome) {
            outcomes.add(outcome);
        }
    };

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Allow or ask
    // -----------------------------------------------------------------------

    @Test
    void aToolInAllowRunsWithoutApproval() throws Exception {
        ToolExecutionResult result = execute(webTurn(), call("1", "readNotes")).get(5, TimeUnit.SECONDS);

        assertThat(runs("readNotes")).hasSize(1);
        assertThat(requests).isEmpty();
        assertThat(responseText(result, 0)).isEqualTo("ran readNotes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"writeNote", "runScript", "fetchUrl", "someMcpTool"})
    void anyOtherToolAsksAndDoesNotRunBeforeApproval(String toolName) throws Exception {
        CompletableFuture<ToolExecutionResult> result = execute(webTurn(), call("1", toolName));

        ApprovalRequest request = nextRequest();
        assertThat(request.toolName()).isEqualTo(toolName);
        assertThat(runs(toolName)).isEmpty();
        assertThat(result).isNotDone();

        assertThat(decide(request, ApprovalDecision.ALLOW_ONCE)).isTrue();
        result.get(5, TimeUnit.SECONDS);
        assertThat(runs(toolName)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Decisions
    // -----------------------------------------------------------------------

    @Test
    void allowOnceRunsTheToolOnce() throws Exception {
        CompletableFuture<ToolExecutionResult> result = execute(webTurn(), call("1", "writeNote", "{\"text\":\"hi\"}"));
        ApprovalRequest request = nextRequest();

        assertThat(decide(request, ApprovalDecision.ALLOW_ONCE)).isTrue();
        assertThat(decide(request, ApprovalDecision.ALLOW_ONCE)).as("reused id").isFalse();

        assertThat(responseText(result.get(5, TimeUnit.SECONDS), 0)).isEqualTo("ran writeNote");
        assertThat(runs("writeNote")).containsExactly("{\"text\":\"hi\"}");
        assertThat(onlyOutcome().approved()).isTrue();
        assertThat(pending.isPending(request.approvalId())).isFalse();
    }

    @Test
    void denyNeverRunsTheTool() throws Exception {
        CompletableFuture<ToolExecutionResult> result = execute(webTurn(), call("1", "writeNote"));

        decide(nextRequest(), ApprovalDecision.DENY);

        assertThat(responseText(result.get(5, TimeUnit.SECONDS), 0)).isEqualTo("The user did not approve this action.");
        assertThat(runs("writeNote")).isEmpty();
        assertThat(onlyOutcome().denial()).isEqualTo(Denial.NOT_APPROVED);
    }

    @Test
    void timeoutNeverRunsTheTool() throws Exception {
        manager = managerFor(new ApprovalProperties(Duration.ofMillis(200), null));

        ToolExecutionResult result = execute(webTurn(), call("1", "writeNote")).get(5, TimeUnit.SECONDS);

        assertThat(runs("writeNote")).isEmpty();
        assertThat(onlyOutcome().denial()).isEqualTo(Denial.TIMED_OUT);
        assertThat(responseText(result, 0)).isEqualTo("No approval was given in time.");
        assertThat(pending.pendingFor(CONVERSATION)).isEmpty();
    }

    @Test
    void aWrongIdAChangedHashOrAnotherConversationHasNoEffect() throws Exception {
        CompletableFuture<ToolExecutionResult> result = execute(webTurn(), call("1", "writeNote", "{\"text\":\"hi\"}"));
        ApprovalRequest request = nextRequest();

        assertThat(pending.decide(CONVERSATION, "not-the-id", request.argumentsHash(), ApprovalDecision.ALLOW_ONCE)).isFalse();
        assertThat(pending.decide(CONVERSATION, request.approvalId(),
                ArgumentsHash.of("writeNote", "{\"text\":\"bye\"}"), ApprovalDecision.ALLOW_ONCE)).isFalse();
        assertThat(pending.decide("other-conversation", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE)).isFalse();

        assertThat(result).isNotDone();
        assertThat(runs("writeNote")).isEmpty();
        assertThat(pending.isPending(request.approvalId())).isTrue();

        decide(request, ApprovalDecision.DENY);
        result.get(5, TimeUnit.SECONDS);
        assertThat(runs("writeNote")).isEmpty();
    }

    @Test
    void theApprovalIsKeyedByToolCallIdAndBoundToTheArguments() throws Exception {
        execute(webTurn(), call("call-42", "writeNote", "{\"b\":2, \"a\":1}"));

        ApprovalRequest request = nextRequest();
        assertThat(request.approvalId()).isEqualTo("call-42");
        assertThat(request.arguments()).isEqualTo("{\"b\":2, \"a\":1}");
        // Canonical: key order and whitespace do not matter, values do
        assertThat(request.argumentsHash())
                .isEqualTo(ArgumentsHash.of("writeNote", "{\"a\":1,\"b\":2}"))
                .isNotEqualTo(ArgumentsHash.of("writeNote", "{\"a\":1,\"b\":3}"))
                .isNotEqualTo(ArgumentsHash.of("Write", "{\"a\":1,\"b\":2}"));
        assertThat(request.decisions()).containsExactly(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY);
        decide(request, ApprovalDecision.DENY);
    }

    // -----------------------------------------------------------------------
    // Nobody to ask
    // -----------------------------------------------------------------------

    @Test
    void noConnectedClientIsDeniedImmediately() throws Exception {
        clientConnected = false;

        long start = System.nanoTime();
        ToolExecutionResult result = execute(webTurn(), call("1", "writeNote")).get(5, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(requests).isEmpty();
        assertThat(runs("writeNote")).isEmpty();
        assertThat(onlyOutcome().denial()).isEqualTo(Denial.NOT_APPROVED);
        assertThat(responseText(result, 0)).isEqualTo("The user did not approve this action.");
    }

    @Test
    void disconnectingDeniesPendingApprovals() throws Exception {
        CompletableFuture<ToolExecutionResult> result = execute(webTurn(), call("1", "writeNote"));
        nextRequest();

        assertThat(pending.cancelConversation(CONVERSATION, Denial.NOT_APPROVED)).isEqualTo(1);

        assertThat(responseText(result.get(5, TimeUnit.SECONDS), 0)).isEqualTo("The user did not approve this action.");
        assertThat(runs("writeNote")).isEmpty();
    }

    @Test
    void aMissingQuestionHandlerNeverRunsTheTool() throws Exception {
        ToolExecutionResult result = execute(new ApprovalTurn(CONVERSATION, null, listener), call("1", "writeNote"))
                .get(5, TimeUnit.SECONDS);

        assertThat(runs("writeNote")).isEmpty();
        assertThat(onlyOutcome().denial()).isEqualTo(Denial.NOT_APPROVED);
    }

    @Test
    void aRequestWithoutAnyTurnNeverRunsTheTool() throws Exception {
        execute(null, call("1", "writeNote")).get(5, TimeUnit.SECONDS);

        assertThat(runs("writeNote")).isEmpty();
    }

    @Test
    void aScheduledTaskCannotAskSoTheCallIsDenied() throws Exception {
        ToolExecutionResult result = execute(new ApprovalTurn("task-1", null, listener), call("1", "writeNote"))
                .get(5, TimeUnit.SECONDS);

        assertThat(runs("writeNote")).isEmpty();
        assertThat(requests).isEmpty();
        assertThat(onlyOutcome().denial()).isEqualTo(Denial.NOT_APPROVED);
    }

    @Test
    void aFailingQuestionHandlerNeverRunsTheTool() throws Exception {
        HumanQuestionHandler broken = _ -> {
            throw new IllegalStateException("socket gone");
        };

        execute(new ApprovalTurn(CONVERSATION, broken, listener), call("1", "writeNote")).get(5, TimeUnit.SECONDS);

        assertThat(runs("writeNote")).isEmpty();
        assertThat(onlyOutcome().denial()).isEqualTo(Denial.NOT_APPROVED);
    }

    @Test
    void refusalsAndResultsKeepTheModelsCallOrder() throws Exception {
        clientConnected = false;

        ToolExecutionResult result = execute(webTurn(),
                call("1", "readNotes"), call("2", "writeNote"), call("3", "readNotes")).get(5, TimeUnit.SECONDS);

        ToolResponseMessage responses = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(responses.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("1", "2", "3");
        assertThat(responseText(result, 0)).isEqualTo("ran readNotes");
        assertThat(responseText(result, 1)).contains("The user did not approve this action.");
        assertThat(responseText(result, 2)).isEqualTo("ran readNotes");
        assertThat(result.returnDirect()).isFalse();
    }

    // -----------------------------------------------------------------------
    // The turn never reaches the tools
    // -----------------------------------------------------------------------

    @Test
    void toolsDoNotSeeTheApprovalTurnInTheirContext() throws Exception {
        execute(webTurn(), call("1", "readNotes")).get(5, TimeUnit.SECONDS);

        assertThat(tool("readNotes").contexts()).singleElement().satisfies(context ->
                assertThat(context).doesNotContainKey(ApprovalTurn.TOOL_CONTEXT_KEY).containsEntry("tenant", "acme"));
    }

    // -----------------------------------------------------------------------

    private ToolCallingManager managerFor(ApprovalProperties properties) {
        return ApprovalGateFixtures.gate(properties, pending).toolCallingManager(ToolCallingManager.builder().build());
    }

    private ApprovalTurn webTurn() {
        return new ApprovalTurn(CONVERSATION, _ -> clientConnected, listener);
    }

    private RecordingTool tool(String name) {
        return tools.computeIfAbsent(name, RecordingTool::new);
    }

    private List<String> runs(String name) {
        return tool(name).runs();
    }

    private static AssistantMessage.ToolCall call(String id, String name) {
        return call(id, name, "{}");
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private CompletableFuture<ToolExecutionResult> execute(ApprovalTurn turn, AssistantMessage.ToolCall... calls) {
        List<ToolCallback> callbacks = new ArrayList<>();
        Arrays.stream(calls).map(AssistantMessage.ToolCall::name).distinct().forEach(name -> callbacks.add(tool(name)));
        Map<String, Object> context = new LinkedHashMap<>(Map.of("tenant", "acme"));
        if (turn != null) {
            context.putAll(turn.toolContext());
        }
        Prompt prompt = new Prompt(List.of(new UserMessage("go")), ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks).toolContext(context).build());
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
        return CompletableFuture.supplyAsync(() -> manager.executeToolCalls(prompt, response), executor);
    }

    private ApprovalRequest nextRequest() throws InterruptedException {
        ApprovalRequest request = requests.poll(5, TimeUnit.SECONDS);
        assertThat(request).as("approval request").isNotNull();
        return request;
    }

    private boolean decide(ApprovalRequest request, ApprovalDecision decision) {
        return pending.decide(request.conversationId(), request.approvalId(), request.argumentsHash(), decision);
    }

    private ApprovalOutcome onlyOutcome() {
        assertThat(outcomes).hasSize(1);
        return outcomes.getFirst();
    }

    private static String responseText(ToolExecutionResult result, int index) {
        return ((ToolResponseMessage) result.conversationHistory().getLast()).getResponses().get(index).responseData();
    }
}

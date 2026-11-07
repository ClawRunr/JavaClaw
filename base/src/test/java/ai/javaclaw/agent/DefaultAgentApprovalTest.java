package ai.javaclaw.agent;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalGate;
import ai.javaclaw.approval.ApprovalGateFixtures;
import ai.javaclaw.approval.ApprovalGateFixtures.RecordingTool;
import ai.javaclaw.approval.ApprovalProperties;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.approval.PendingApprovals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The approval gate end to end: {@link DefaultAgent} through a real {@link ChatClient} and
 * tool-calling advisor, with only the model faked.
 */
class DefaultAgentApprovalTest {

    static final String NOTE_ARGS = "{\"text\":\"buy milk\"}";

    final PendingApprovals pending = new PendingApprovals();
    final RecordingTool writeNote = new RecordingTool("writeNote");
    final RecordingTool readNotes = new RecordingTool("readNotes");
    final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    /** Tool names the model was offered, one entry per model call. */
    final List<List<String>> offered = new CopyOnWriteArrayList<>();
    LuceneToolIndex toolIndex;

    @AfterEach
    void closeIndex() throws Exception {
        if (toolIndex != null) {
            toolIndex.close();
        }
    }

    @Test
    void toolCallApprovalRequestedAllowToolRunsAnswer() {
        DefaultAgent agent = agent(ApprovalProperties.defaults(), plainAdvisor(ApprovalProperties.defaults()),
                new NoteModel(true, this::callWriteNote));

        CompletableFuture<Void> turn = CompletableFuture.runAsync(() ->
                agent.respondTo("web", "Note: buy milk", events::add, _ -> true));

        ApprovalRequest request = awaitApprovalRequest();
        assertThat(request.toolName()).isEqualTo("writeNote");
        assertThat(request.arguments()).isEqualTo(NOTE_ARGS);
        assertThat(writeNote.runs()).isEmpty();

        assertThat(pending.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.ALLOW_ONCE)).isTrue();
        turn.join();

        assertThat(writeNote.runs()).containsExactly(NOTE_ARGS);
        assertThat(events).containsExactly(
                new AgentEvent.ToolCall("call-1", "writeNote", NOTE_ARGS),
                new AgentEvent.ApprovalRequested(request),
                new AgentEvent.ApprovalResolved("call-1", "call-1", "writeNote", null),
                new AgentEvent.ToolResult("call-1", "writeNote", "ran writeNote"),
                new AgentEvent.Token("Noted: ran writeNote"),
                new AgentEvent.Done());
    }

    @Test
    void aDeniedCallReachesTheModelAsTheToolResultAndTheConversationCarriesOn() {
        DefaultAgent agent = agent(ApprovalProperties.defaults(), plainAdvisor(ApprovalProperties.defaults()),
                new NoteModel(false, this::callWriteNote));

        CompletableFuture<Void> turn = CompletableFuture.runAsync(() ->
                agent.respondTo("web", "Note: buy milk", events::add, _ -> true));
        ApprovalRequest request = awaitApprovalRequest();
        pending.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.DENY);
        turn.join();

        assertThat(writeNote.runs()).isEmpty();
        assertThat(events).filteredOn(AgentEvent.Token.class::isInstance).singleElement()
                .satisfies(token -> assertThat(((AgentEvent.Token) token).text())
                        .startsWith("Noted: Tool 'writeNote' was NOT run: the user denied it"));
        assertThat(events.getLast()).isEqualTo(new AgentEvent.Done());
    }

    @Test
    void aScheduledTaskCannotAskSoItsCallIsRefusedWithTheReason() {
        DefaultAgent agent = agent(ApprovalProperties.defaults(), plainAdvisor(ApprovalProperties.defaults()),
                new NoteModel(false, this::callWriteNote));

        Answer answer = agent.prompt("task-1", "Note: buy milk", Answer.class, events::add);

        assertThat(writeNote.runs()).isEmpty();
        assertThat(answer.text()).contains("nobody can be asked");
        assertThat(events).filteredOn(AgentEvent.ApprovalResolved.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((AgentEvent.ApprovalResolved) e).denial().reason())
                        .isEqualTo(Denial.Reason.NOBODY_TO_ASK));
    }

    @Test
    void deniedToolsAreNotSentToTheModel() {
        ApprovalProperties denyWrite = ApprovalGateFixtures.rules(List.of(), List.of(), List.of("writeNote"));
        DefaultAgent agent = agent(denyWrite, plainAdvisor(denyWrite), new NoteModel(true, _ -> null));

        agent.respondTo("web", "hello", events::add, _ -> true);

        assertThat(offered).isNotEmpty().allSatisfy(names ->
                assertThat(names).contains("readNotes").doesNotContain("writeNote"));
    }

    @Test
    void toolsFoundThroughToolSearchAreGated() throws Exception {
        ApprovalProperties defaults = ApprovalProperties.defaults();
        toolIndex = new LuceneToolIndex(0.0f);
        ApprovalGate gate = ApprovalGateFixtures.gate(defaults, pending);
        ToolSearchToolCallingAdvisor searchAdvisor = ToolSearchToolCallingAdvisor.builder()
                .toolCallingManager(gate.toolCallingManager(ToolCallingManager.builder().build()))
                .toolIndex(toolIndex)
                .build();
        DefaultAgent agent = agent(defaults, searchAdvisor, new NoteModel(true, this::searchThenWriteNote));

        CompletableFuture<Void> turn = CompletableFuture.runAsync(() ->
                agent.respondTo("web", "Note: buy milk", events::add, _ -> true));

        // The search itself is read-only and runs; the tool it found waits for approval
        ApprovalRequest request = awaitApprovalRequest();
        assertThat(request.toolName()).isEqualTo("writeNote");
        assertThat(writeNote.runs()).isEmpty();
        assertThat(offered.getFirst()).doesNotContain("writeNote");

        pending.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.DENY);
        turn.get(10, TimeUnit.SECONDS);
        assertThat(writeNote.runs()).isEmpty();
        assertThat(events.getLast()).isEqualTo(new AgentEvent.Done());
    }

    // -----------------------------------------------------------------------

    private DefaultAgent agent(ApprovalProperties properties, ToolCallingAdvisor toolAdvisor, ChatModel model) {
        ApprovalGate gate = ApprovalGateFixtures.gate(properties, pending);
        List<Advisor> advisors = List.of(gate.deniedToolFilter(), toolAdvisor);
        return new DefaultAgent(ChatClient.builder(model)
                .defaultTools(writeNote, readNotes)
                .defaultAdvisors(advisors)
                .build());
    }

    private ToolCallingAdvisor plainAdvisor(ApprovalProperties properties) {
        return ToolCallingAdvisor.builder()
                .toolCallingManager(ApprovalGateFixtures.gate(properties, pending)
                        .toolCallingManager(ToolCallingManager.builder().build()))
                .build();
    }

    private ApprovalRequest awaitApprovalRequest() {
        await().atMost(10, TimeUnit.SECONDS).until(() ->
                events.stream().anyMatch(AgentEvent.ApprovalRequested.class::isInstance));
        return events.stream()
                .filter(AgentEvent.ApprovalRequested.class::isInstance)
                .map(e -> ((AgentEvent.ApprovalRequested) e).request())
                .findFirst().orElseThrow();
    }

    /** First round: write the note. */
    private AssistantMessage.ToolCall callWriteNote(Prompt prompt) {
        return responded(prompt, "writeNote") ? null : new AssistantMessage.ToolCall("call-1", "function", "writeNote", NOTE_ARGS);
    }

    /** First round: search for a tool; second: call the one it found. */
    private AssistantMessage.ToolCall searchThenWriteNote(Prompt prompt) {
        if (!responded(prompt, "toolSearchTool")) {
            String queryParameter = firstParameter(prompt, "toolSearchTool");
            return new AssistantMessage.ToolCall("call-search", "function", "toolSearchTool",
                    "{\"" + queryParameter + "\":\"write a note to the notebook\"}");
        }
        return callWriteNote(prompt);
    }

    private static boolean responded(Prompt prompt, String toolName) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                .anyMatch(r -> r.name().equals(toolName));
    }

    @SuppressWarnings("unchecked")
    private static String firstParameter(Prompt prompt, String toolName) {
        ToolCallback tool = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                .filter(t -> t.getToolDefinition().name().equals(toolName)).findFirst().orElseThrow();
        Map<String, Object> schema = JsonMapper.builder().build().readValue(tool.getToolDefinition().inputSchema(), Map.class);
        return ((Map<String, Object>) schema.get("properties")).keySet().iterator().next();
    }

    record Answer(String text) {}

    /**
     * Calls whatever tool {@code nextCall} picks; once it picks none, answers with the last tool
     * result it saw so tests can check what reached the model.
     */
    final class NoteModel implements ChatModel {

        private final boolean streams;
        private final Function<Prompt, AssistantMessage.ToolCall> nextCall;

        NoteModel(boolean streams, Function<Prompt, AssistantMessage.ToolCall> nextCall) {
            this.streams = streams;
            this.nextCall = nextCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            offered.add(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                    .map(t -> t.getToolDefinition().name()).toList());
            AssistantMessage.ToolCall call = nextCall.apply(prompt);
            if (call != null) {
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
            }
            String lastResult = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                    .reduce((a, b) -> b)
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .orElse("nothing");
            String text = "Noted: " + lastResult;
            // A structured-output request (prompt(..., Class)) expects JSON back
            boolean wantsJson = prompt.getInstructions().stream().anyMatch(m -> m.getText() != null && m.getText().contains("JSON"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    wantsJson ? JsonMapper.builder().build().writeValueAsString(new Answer(text)) : text))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return streams ? Flux.just(call(prompt)) : ChatModel.super.stream(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}

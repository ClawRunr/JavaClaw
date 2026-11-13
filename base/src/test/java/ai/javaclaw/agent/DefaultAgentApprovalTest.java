package ai.javaclaw.agent;

import ai.javaclaw.agent.Agent.DecisionResult;
import ai.javaclaw.agent.memory.FileSystemChatMemoryRepository;
import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalFixtures;
import ai.javaclaw.approval.ApprovalFixtures.RecordingTool;
import ai.javaclaw.approval.ApprovalPolicy;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pausing for approval end to end: {@link DefaultAgent} through a real {@link ChatClient}, the
 * tool-calling advisor with the approval policy and the file-backed chat memory, with only the
 * model faked. The model streams.
 */
@SuppressWarnings("removal")
class DefaultAgentApprovalTest {

    static final String NOTE_ARGS = "{\"text\":\"buy milk\"}";
    static final AssistantMessage.ToolCall WRITE_NOTE = new AssistantMessage.ToolCall("call-1", "function", "writeNote", NOTE_ARGS);
    static final AssistantMessage.ToolCall READ_NOTES = new AssistantMessage.ToolCall("call-0", "function", "readNotes", "{}");

    /** Every tool run, in order, across tools. */
    final List<String> runs = new CopyOnWriteArrayList<>();
    final RecordingTool writeNote = new RecordingTool("writeNote", runs);
    final RecordingTool readNotes = new RecordingTool("readNotes", runs);
    final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    /** What the model was given, one entry per model call. */
    final List<List<Message>> modelSaw = new CopyOnWriteArrayList<>();
    /** Tool names the model was offered, one entry per model call. */
    final List<List<String>> offered = new CopyOnWriteArrayList<>();
    final ApprovalPolicy policy = ApprovalFixtures.defaultPolicy();

    @TempDir
    Path workspace;
    MessageWindowChatMemory memory;
    LuceneToolIndex toolIndex;

    @BeforeEach
    void memory() throws Exception {
        memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new FileSystemChatMemoryRepository(new FileSystemResource(workspace.toString() + "/")))
                .build();
    }

    @AfterEach
    void closeIndex() throws Exception {
        if (toolIndex != null) {
            toolIndex.close();
        }
    }

    // -----------------------------------------------------------------------
    // Pausing
    // -----------------------------------------------------------------------

    @Test
    void aToolInAllowRunsWithoutPausing() {
        DefaultAgent agent = agent(firstRound(READ_NOTES));

        agent.respondTo("web", "What are my notes?", events::add, _ -> true);

        assertThat(readNotes.runs()).containsExactly("{}");
        assertThat(events).noneMatch(AgentEvent.ApprovalRequested.class::isInstance)
                .contains(new AgentEvent.Token("ran readNotes"))
                .last().isEqualTo(new AgentEvent.Done());
    }

    @Test
    void anyOtherToolPausesTheTurnAndAsks() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));

        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);

        ApprovalRequest request = onlyRequest();
        assertThat(request.toolName()).isEqualTo("writeNote");
        assertThat(request.arguments()).isEqualTo(NOTE_ARGS);
        assertThat(request.conversationId()).isEqualTo("web");
        assertThat(writeNote.runs()).isEmpty();
        assertThat(events).containsExactly(
                new AgentEvent.Token("On it."),
                new AgentEvent.ToolCall("call-1", "writeNote", NOTE_ARGS),
                new AgentEvent.ApprovalRequested(request),
                new AgentEvent.Done());
        assertThat(agent.waitingForApproval("web")).containsExactly(request);
    }

    // -----------------------------------------------------------------------
    // Deciding
    // -----------------------------------------------------------------------

    @Test
    void toolCallPausesAllowRunsTheToolAndTheAnswerStreams() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));
        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();
        events.clear();

        DecisionResult result = agent.decide("web", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add);

        assertThat(result).isEqualTo(DecisionResult.RESUMED);
        assertThat(writeNote.runs()).containsExactly(NOTE_ARGS);
        assertThat(events).containsExactly(
                new AgentEvent.ApprovalResolved(request.approvalId(), "call-1", "writeNote", null),
                new AgentEvent.ToolResult("call-1", "writeNote", "ran writeNote"),
                new AgentEvent.Token("Noted: "),
                new AgentEvent.Token("ran writeNote"),
                new AgentEvent.Done());
        assertThat(agent.waitingForApproval("web")).isEmpty();
    }

    @Test
    void theResumedRequestCarriesThePausedReplyAndItsResultsAfterTheUserMessage() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));
        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();

        agent.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.ALLOW_ONCE, _ -> true, events::add);

        List<Message> resumed = modelSaw.getLast();
        int user = indexOf(resumed, m -> m instanceof UserMessage u && u.getText().equals("Note: buy milk"));
        int reply = indexOf(resumed, m -> m instanceof AssistantMessage a && a.hasToolCalls());
        assertThat(user).isNotNegative().isLessThan(reply);
        assertThat(resumed.get(reply + 1)).isInstanceOf(ToolResponseMessage.class);
        assertThat(resumed).hasSize(reply + 2);
        // Memory keeps text only: the next turn sees the question and the answers, without tool calls
        assertThat(memory.get("web")).extracting(Message::getText)
                .containsExactly("Note: buy milk", "On it.", "Noted: ran writeNote");
    }

    @Test
    void denyResumesWithTheDenyMessageAndTheToolDoesNotRun() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));
        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();
        events.clear();

        DecisionResult result = agent.decide("web", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.DENY, _ -> true, events::add);

        assertThat(result).isEqualTo(DecisionResult.RESUMED);
        assertThat(writeNote.runs()).isEmpty();
        assertThat(events).contains(
                new AgentEvent.ApprovalResolved(request.approvalId(), "call-1", "writeNote", Denial.NOT_APPROVED),
                new AgentEvent.ToolResult("call-1", "writeNote", Denial.NOT_APPROVED.message()));
        assertThat(lastToolResults()).containsExactly(Denial.NOT_APPROVED.message());
        assertThat(events.getLast()).isEqualTo(new AgentEvent.Done());
    }

    @Test
    void aReplyWithAnAllowedAndAFlaggedCallRunsBothInTheModelsOrderOnceApproved() {
        DefaultAgent agent = agent(firstRound(READ_NOTES, WRITE_NOTE));
        agent.respondTo("web", "Read, then note", events::add, _ -> true);

        // The whole reply pauses: not even the allowed call runs before the decision
        ApprovalRequest request = onlyRequest();
        assertThat(request.toolName()).isEqualTo("writeNote");
        assertThat(runs).isEmpty();

        agent.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.ALLOW_ONCE, _ -> true, events::add);

        assertThat(runs).containsExactly("readNotes:{}", "writeNote:" + NOTE_ARGS);
        assertThat(lastToolResults()).containsExactly("ran readNotes", "ran writeNote");
    }

    @Test
    void aSecondDecisionForTheSameCallHasNoEffect() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));
        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();
        agent.decide("web", request.approvalId(), request.argumentsHash(), ApprovalDecision.ALLOW_ONCE, _ -> true, events::add);
        int modelCalls = modelSaw.size();

        DecisionResult again = agent.decide("web", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add);
        DecisionResult deny = agent.decide("web", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.DENY, _ -> true, events::add);

        assertThat(again).isEqualTo(DecisionResult.IGNORED);
        assertThat(deny).isEqualTo(DecisionResult.IGNORED);
        assertThat(writeNote.runs()).hasSize(1);
        assertThat(modelSaw).hasSize(modelCalls);
    }

    @Test
    void aDecisionForAnotherConversationOrWithAnotherHashHasNoEffect() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));
        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();

        assertThat(agent.decide("telegram-42", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add)).isEqualTo(DecisionResult.IGNORED);
        assertThat(agent.decide("web", request.approvalId(), "another-hash",
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add)).isEqualTo(DecisionResult.IGNORED);
        assertThat(agent.decide("web", "unknown-id", request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add)).isEqualTo(DecisionResult.IGNORED);

        assertThat(writeNote.runs()).isEmpty();
        assertThat(agent.waitingForApproval("web")).containsExactly(request);
    }

    @Test
    void aReplyWithTwoFlaggedCallsResumesOnlyOnceBothAreDecided() {
        AssistantMessage.ToolCall second = new AssistantMessage.ToolCall("call-2", "function", "writeNote", "{\"text\":\"eggs\"}");
        DefaultAgent agent = agent(firstRound(WRITE_NOTE, second));
        agent.respondTo("web", "Two notes", events::add, _ -> true);
        List<ApprovalRequest> requests = agent.waitingForApproval("web");
        assertThat(requests).hasSize(2);

        assertThat(agent.decide("web", requests.get(0).approvalId(), requests.get(0).argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add)).isEqualTo(DecisionResult.RECORDED);
        assertThat(writeNote.runs()).isEmpty();
        assertThat(agent.waitingForApproval("web")).containsExactly(requests.get(1));

        assertThat(agent.decide("web", requests.get(1).approvalId(), requests.get(1).argumentsHash(),
                ApprovalDecision.DENY, _ -> true, events::add)).isEqualTo(DecisionResult.RESUMED);
        assertThat(writeNote.runs()).containsExactly(NOTE_ARGS);
        assertThat(lastToolResults()).containsExactly("ran writeNote", Denial.NOT_APPROVED.message());
    }

    // -----------------------------------------------------------------------
    // Moving on
    // -----------------------------------------------------------------------

    @Test
    void aNewMessageDuringAPauseDeniesTheWaitingCallsFirst() {
        DefaultAgent agent = agent(prompt -> prompt.getInstructions().getLast() instanceof UserMessage user
                && user.getText().startsWith("Note") ? List.of(WRITE_NOTE) : List.of());
        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);
        ApprovalRequest request = onlyRequest();
        events.clear();

        agent.respondTo("web", "Never mind, what time is it?", events::add, _ -> true);

        assertThat(writeNote.runs()).isEmpty();
        assertThat(events.getFirst()).isEqualTo(new AgentEvent.ApprovalResolved(
                request.approvalId(), "call-1", "writeNote", Denial.MOVED_ON));
        List<Message> sent = modelSaw.getLast();
        int results = indexOf(sent, ToolResponseMessage.class::isInstance);
        assertThat(((ToolResponseMessage) sent.get(results)).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("The user moved on without approving.");
        assertThat(sent.get(results + 1).getText()).isEqualTo("Never mind, what time is it?");
        assertThat(agent.waitingForApproval("web")).isEmpty();
        // The old decision no longer resumes anything
        assertThat(agent.decide("web", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add)).isEqualTo(DecisionResult.IGNORED);
        assertThat(writeNote.runs()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Nobody to ask
    // -----------------------------------------------------------------------

    @Test
    void whereNobodyCanBeAskedTheCallIsDeniedAndTheTurnCompletes() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));

        // A Telegram group or Discord server channel: the chat says no one can be asked there
        agent.respondTo("telegram-group", "Note: buy milk", events::add, _ -> false);

        assertThat(writeNote.runs()).isEmpty();
        assertThat(events).noneMatch(AgentEvent.ApprovalRequested.class::isInstance)
                .contains(new AgentEvent.ApprovalResolved(null, "call-1", "writeNote", Denial.NOBODY_TO_ASK))
                .last().isEqualTo(new AgentEvent.Done());
        assertThat(lastToolResults()).containsExactly(
                "No one could be asked to approve this action here, so it was not run.");
        assertThat(agent.waitingForApproval("telegram-group")).isEmpty();
    }

    @Test
    void aScheduledTaskCannotAskSoItsCallIsDeniedAndTheTaskFinishes() {
        DefaultAgent agent = agent(firstRound(WRITE_NOTE));

        Answer answer = agent.prompt("task-1", "Note: buy milk", Answer.class, events::add);

        assertThat(writeNote.runs()).isEmpty();
        assertThat(answer.text()).isEqualTo("Noted: " + Denial.NOBODY_TO_ASK.message());
        assertThat(events).filteredOn(AgentEvent.ApprovalResolved.class::isInstance).singleElement()
                .isEqualTo(new AgentEvent.ApprovalResolved(null, "call-1", "writeNote", Denial.NOBODY_TO_ASK));
    }

    @Test
    void aModelThatKeepsAskingWhereNobodyCanAnswerIsStopped() {
        DefaultAgent agent = agent(_ -> List.of(WRITE_NOTE));

        agent.respondTo("task-1", "Note: buy milk", events::add);

        assertThat(writeNote.runs()).isEmpty();
        assertThat(events.getLast()).isInstanceOf(AgentEvent.Failed.class);
        assertThat(modelSaw).hasSize(DefaultAgent.MAX_REFUSED_PAUSES + 1);
    }

    // -----------------------------------------------------------------------
    // Tool search
    // -----------------------------------------------------------------------

    @Test
    void aToolFoundThroughToolSearchPausesLikeAnyOtherAndRunsOnceAllowed() {
        toolIndex = new LuceneToolIndex(0.0f);
        ToolSearchToolCallingAdvisor searchAdvisor = ToolSearchToolCallingAdvisor.builder()
                .toolCallingManager(policy.toolCallingManager())
                .toolIndex(toolIndex)
                .build();
        DefaultAgent agent = agent(searchAdvisor, this::searchThenWriteNote);

        agent.respondTo("web", "Note: buy milk", events::add, _ -> true);

        // The search itself is in allow and runs; the tool it found waits for approval
        ApprovalRequest request = onlyRequest();
        assertThat(request.toolName()).isEqualTo("writeNote");
        assertThat(offered.getFirst()).doesNotContain("writeNote");
        assertThat(writeNote.runs()).isEmpty();

        assertThat(agent.decide("web", request.approvalId(), request.argumentsHash(),
                ApprovalDecision.ALLOW_ONCE, _ -> true, events::add)).isEqualTo(DecisionResult.RESUMED);
        assertThat(writeNote.runs()).containsExactly(NOTE_ARGS);
        assertThat(events.getLast()).isEqualTo(new AgentEvent.Done());
    }

    // -----------------------------------------------------------------------

    private DefaultAgent agent(Function<Prompt, List<AssistantMessage.ToolCall>> script) {
        return agent(ToolCallingAdvisor.builder().toolCallingManager(policy.toolCallingManager()).build(), script);
    }

    private DefaultAgent agent(ToolCallingAdvisor toolAdvisor, Function<Prompt, List<AssistantMessage.ToolCall>> script) {
        return new DefaultAgent(ChatClient.builder(new NoteModel(script))
                .defaultTools(writeNote, readNotes)
                .defaultAdvisors(toolAdvisor, MessageChatMemoryAdvisor.builder(memory).build())
                .build(), policy);
    }

    /** The calls to make in the first round of a turn; the answer once their results are in. */
    private static Function<Prompt, List<AssistantMessage.ToolCall>> firstRound(AssistantMessage.ToolCall... calls) {
        return prompt -> prompt.getInstructions().getLast() instanceof UserMessage ? List.of(calls) : List.of();
    }

    /** First round: search for a tool; then call the one it found. */
    private List<AssistantMessage.ToolCall> searchThenWriteNote(Prompt prompt) {
        if (!responded(prompt, "toolSearchTool")) {
            String queryParameter = firstParameter(prompt, "toolSearchTool");
            return List.of(new AssistantMessage.ToolCall("call-search", "function", "toolSearchTool",
                    "{\"" + queryParameter + "\":\"write a note to the notebook\"}"));
        }
        return responded(prompt, "writeNote") ? List.of() : List.of(WRITE_NOTE);
    }

    private ApprovalRequest onlyRequest() {
        return events.stream()
                .filter(AgentEvent.ApprovalRequested.class::isInstance)
                .map(e -> ((AgentEvent.ApprovalRequested) e).request())
                .reduce((a, b) -> {
                    throw new AssertionError("More than one approval request");
                })
                .orElseThrow(() -> new AssertionError("No approval request in " + events));
    }

    /** The tool results the model was last given. */
    private List<String> lastToolResults() {
        return modelSaw.getLast().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .reduce((a, b) -> b)
                .map(m -> ((ToolResponseMessage) m).getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData).toList())
                .orElse(List.of());
    }

    private static int indexOf(List<Message> messages, java.util.function.Predicate<Message> test) {
        for (int i = 0; i < messages.size(); i++) {
            if (test.test(messages.get(i))) {
                return i;
            }
        }
        return -1;
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
     * Calls whatever tools {@code script} picks, after saying "On it."; once it picks none, answers
     * with the tool results it was last given, so tests can check what reached the model. Streams
     * text in pieces, and tool calls as one chunk, like the bundled providers.
     */
    final class NoteModel implements ChatModel {

        private final Function<Prompt, List<AssistantMessage.ToolCall>> script;

        NoteModel(Function<Prompt, List<AssistantMessage.ToolCall>> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return Flux.fromIterable(chunks(prompt)).reduce(this::merge).block();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.fromIterable(chunks(prompt));
        }

        private List<ChatResponse> chunks(Prompt prompt) {
            modelSaw.add(List.copyOf(prompt.getInstructions()));
            offered.add(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                    .map(t -> t.getToolDefinition().name()).toList());
            List<AssistantMessage.ToolCall> calls = script.apply(prompt);
            if (!calls.isEmpty()) {
                return List.of(text("On it."), response(AssistantMessage.builder().content("").toolCalls(calls).build()));
            }
            List<String> results = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .reduce((a, b) -> b)
                    .map(m -> ((ToolResponseMessage) m).getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::responseData).toList())
                    .orElse(List.of("nothing"));
            String answer = String.join(", ", results);
            // A structured-output request (prompt(..., Class)) expects JSON back
            boolean wantsJson = prompt.getInstructions().stream()
                    .anyMatch(m -> m.getText() != null && m.getText().contains("JSON"));
            if (wantsJson) {
                return List.of(text(JsonMapper.builder().build().writeValueAsString(new Answer("Noted: " + answer))));
            }
            return List.of(text("Noted: "), text(answer));
        }

        private ChatResponse merge(ChatResponse a, ChatResponse b) {
            AssistantMessage first = a.getResult().getOutput();
            AssistantMessage second = b.getResult().getOutput();
            return response(AssistantMessage.builder()
                    .content(first.getText() + second.getText())
                    .toolCalls(second.hasToolCalls() ? second.getToolCalls() : first.getToolCalls())
                    .build());
        }

        private static ChatResponse text(String text) {
            return response(new AssistantMessage(text));
        }

        private static ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}

package ai.javaclaw.agent;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalPolicy;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.approval.HumanQuestionHandler;
import ai.javaclaw.approval.PausedTurn;
import ai.javaclaw.approval.PausedTurns;
import ai.javaclaw.utils.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The main agent. Tool calls that need approval pause the turn instead of blocking a thread: the
 * tool-calling loop returns the reply with the calls that wait (Spring AI's tool call
 * confirmation), this agent keeps the reply in {@link PausedTurns} and ends the turn, and
 * {@link #decide} runs the reply's calls as decided and continues the conversation.
 * <p>
 * The chat memory sits outside the tool loop and keeps text only, so a resumed request carries the
 * paused reply and its tool results itself. Tool rounds that ran earlier in the paused turn are not
 * part of it; the model does not see them after resuming.
 */
@Component
public class DefaultAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgent.class);

    /**
     * How often one turn may pause where nobody can be asked before it gives up: each pause is
     * denied and sent back, and a model that keeps asking for tools would otherwise never stop.
     */
    static final int MAX_REFUSED_PAUSES = 10;

    private final ChatClient chatClient;
    private final ToolCallingManager toolCallingManager;
    private final PausedTurns paused = new PausedTurns();

    @Autowired
    public DefaultAgent(ChatClient chatClient, ApprovalPolicy approvalPolicy) {
        this(chatClient, approvalPolicy.toolCallingManager());
    }

    /** @param toolCallingManager runs a paused reply's calls once decided; the advisors' manager */
    public DefaultAgent(ChatClient chatClient, ToolCallingManager toolCallingManager) {
        this.chatClient = chatClient;
        this.toolCallingManager = toolCallingManager;
    }

    /** For a client whose tools never need approval. */
    public DefaultAgent(ChatClient chatClient) {
        this(chatClient, ToolCallingManager.builder().build());
    }

    @Override
    public String respondTo(String conversationId, String question) {
        return run(conversationId, question, _ -> {}, null, false);
    }

    @Override
    public void respondTo(String conversationId, String question, Consumer<AgentEvent> events) {
        respondTo(conversationId, question, events, null);
    }

    @Override
    public void respondTo(String conversationId, String question, Consumer<AgentEvent> events,
                          HumanQuestionHandler questions) {
        try {
            run(conversationId, question, events, questions, true);
            events.accept(new AgentEvent.Done());
        } catch (RuntimeException e) {
            log.warn("Response failed for conversation {}", conversationId, e);
            events.accept(new AgentEvent.Failed(Errors.summarize(e)));
        }
    }

    @Override
    public String answer(String conversationId, String question, HumanQuestionHandler questions,
                         Consumer<AgentEvent> events) {
        return run(conversationId, question, events, questions, false);
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result) {
        return prompt(conversationId, input, result, _ -> {});
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result, Consumer<AgentEvent> events) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(result);
        String text = run(conversationId, input + System.lineSeparator() + converter.getFormat(), events, null, false);
        return converter.convert(text);
    }

    @Override
    public DecisionResult decide(String conversationId, String approvalId, String argumentsHash,
                                 ApprovalDecision decision, HumanQuestionHandler questions,
                                 Consumer<AgentEvent> events) {
        PausedTurn turn = paused.get(conversationId).orElse(null);
        Optional<ApprovalRequest> decided = turn == null ? Optional.empty()
                : turn.decide(approvalId, argumentsHash, decision);
        if (decided.isEmpty()) {
            log.info("Ignoring decision for {} in conversation {}: nothing waiting matches it", approvalId, conversationId);
            return DecisionResult.IGNORED;
        }
        ApprovalRequest request = decided.get();
        log.info("Conversation {}: {} {} ({})", conversationId, request.toolName(),
                decision == ApprovalDecision.ALLOW_ONCE ? "allowed once" : "denied", approvalId);
        emit(events, new AgentEvent.ApprovalResolved(approvalId, request.toolCallId(), request.toolName(),
                decision == ApprovalDecision.ALLOW_ONCE ? null : Denial.NOT_APPROVED));
        // Only whoever takes the paused turn out resumes it, so it runs once
        if (!turn.isDecided() || !paused.remove(turn)) {
            return DecisionResult.RECORDED;
        }
        try {
            continueTurn(conversationId, resume(turn, events), events, questions, true);
            events.accept(new AgentEvent.Done());
        } catch (RuntimeException e) {
            log.warn("Resuming conversation {} failed", conversationId, e);
            events.accept(new AgentEvent.Failed(Errors.summarize(e)));
        }
        return DecisionResult.RESUMED;
    }

    @Override
    public List<ApprovalRequest> waitingForApproval(String conversationId) {
        return paused.waitingIn(conversationId);
    }

    // -----------------------------------------------------------------------
    // Turns
    // -----------------------------------------------------------------------

    /** A new message: moves on from any pause first, then answers. */
    private String run(String conversationId, String question, Consumer<AgentEvent> events,
                       HumanQuestionHandler questions, boolean streaming) {
        List<Message> messages = new ArrayList<>(moveOn(conversationId, events));
        messages.add(new UserMessage(question));
        return continueTurn(conversationId, new Next(messages, null), events, questions, streaming);
    }

    /**
     * A pause nobody decided is denied before a new message is handled, so the model learns the
     * calls did not run and the conversation stays valid.
     *
     * @return the paused reply and its results, to send ahead of the new message; empty when not paused
     */
    private List<Message> moveOn(String conversationId, Consumer<AgentEvent> events) {
        Optional<PausedTurn> turn = paused.take(conversationId);
        if (turn.isEmpty()) {
            return List.of();
        }
        List<ApprovalRequest> denied = turn.get().denyWaiting(Denial.MOVED_ON);
        log.info("Conversation {} moved on; denied {} waiting approval(s)", conversationId, denied.size());
        denied.forEach(request -> emit(events, new AgentEvent.ApprovalResolved(request.approvalId(),
                request.toolCallId(), request.toolName(), Denial.MOVED_ON)));
        Next next = resume(turn.get(), events);
        if (next.answer() != null) {
            // The tools answered directly, so there is no reply to send on
            return List.of();
        }
        return next.messages();
    }

    /**
     * Sends {@code next} to the model until a reply is final or pauses where someone can be asked.
     *
     * @return the text of the last reply: the answer, or what the model said before pausing
     */
    private String continueTurn(String conversationId, Next next, Consumer<AgentEvent> events,
                                HumanQuestionHandler questions, boolean streaming) {
        for (int refused = 0; ; refused++) {
            if (next.answer() != null) {
                if (streaming) {
                    emit(events, new AgentEvent.Token(next.answer()));
                }
                return next.answer();
            }
            Reply reply = send(conversationId, next.messages(), events, streaming);
            PausedTurn turn = reply.paused();
            if (turn == null || (canAsk(questions, conversationId) && ask(turn, events))) {
                return reply.text();
            }
            if (refused >= MAX_REFUSED_PAUSES) {
                throw new IllegalStateException("Stopped after " + MAX_REFUSED_PAUSES
                        + " tool requests that no one could approve here");
            }
            List<ApprovalRequest> denied = turn.denyWaiting(Denial.NOBODY_TO_ASK);
            log.info("Conversation {}: nobody to ask; denied {}", conversationId,
                    denied.stream().map(ApprovalRequest::toolName).collect(Collectors.joining(", ")));
            denied.forEach(request -> emit(events, new AgentEvent.ApprovalResolved(null, request.toolCallId(),
                    request.toolName(), Denial.NOBODY_TO_ASK)));
            next = resume(turn, events);
        }
    }

    private static boolean canAsk(HumanQuestionHandler questions, String conversationId) {
        if (questions == null) {
            return false;
        }
        try {
            return questions.canAsk(conversationId);
        } catch (RuntimeException e) {
            log.warn("Approval handler failed for conversation {}; nobody to ask", conversationId, e);
            return false;
        }
    }

    /** Keeps the pause and shows its requests; {@code false} when they could not be shown. */
    private boolean ask(PausedTurn turn, Consumer<AgentEvent> events) {
        paused.pause(turn);
        try {
            turn.requests().forEach(request -> events.accept(new AgentEvent.ApprovalRequested(request)));
        } catch (RuntimeException e) {
            log.warn("Could not show the approval requests of conversation {}", turn.conversationId(), e);
            return !paused.remove(turn);
        }
        log.info("Conversation {} paused for approval of {}", turn.conversationId(),
                turn.requests().stream().map(ApprovalRequest::toolName).collect(Collectors.joining(", ")));
        return true;
    }

    /**
     * Runs a decided reply's calls: allowed ones run, denied ones answer with their message. The
     * prompt carries the turn's full tool set, so a tool found through tool search runs too.
     */
    private Next resume(PausedTurn turn, Consumer<AgentEvent> events) {
        ToolExecutionResult result = toolCallingManager.executeToolCalls(
                new Prompt(List.of(), turn.options()), turn.reply(), turn.decisions());
        List<Message> history = result.conversationHistory();
        if (!history.isEmpty() && history.getLast() instanceof ToolResponseMessage responses) {
            responses.getResponses().forEach(response -> emit(events,
                    new AgentEvent.ToolResult(response.id(), response.name(), response.responseData())));
        }
        if (result.returnDirect()) {
            return new Next(List.of(), ToolExecutionResult.buildGenerations(result).stream()
                    .map(generation -> generation.getOutput().getText())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(System.lineSeparator())));
        }
        return new Next(history, null);
    }

    // -----------------------------------------------------------------------
    // One request to the model
    // -----------------------------------------------------------------------

    /** What to send next: messages for the model, or an answer the tools gave directly. */
    private record Next(List<Message> messages, String answer) {
    }

    /** The text of a reply, and the pause it ended in, if any. */
    private record Reply(String text, PausedTurn paused) {
    }

    private Reply send(String conversationId, List<Message> messages, Consumer<AgentEvent> events, boolean streaming) {
        if (streaming) {
            try {
                return stream(conversationId, messages, events);
            } catch (UnsupportedOperationException e) {
                // The configured model cannot stream — fall back to the blocking call
            }
        }
        ToolOptionsCapture capture = new ToolOptionsCapture();
        ChatResponse response = request(conversationId, messages, events, capture).call().chatResponse();
        String text = response == null || response.getResult() == null ? ""
                : Optional.ofNullable(response.getResult().getOutput().getText()).orElse("");
        if (streaming && !text.isEmpty()) {
            emit(events, new AgentEvent.Token(text));
        }
        return new Reply(text, pause(conversationId, response, response, capture));
    }

    private Reply stream(String conversationId, List<Message> messages, Consumer<AgentEvent> events) {
        ToolOptionsCapture capture = new ToolOptionsCapture();
        StringBuilder text = new StringBuilder();
        AtomicReference<ChatResponse> last = new AtomicReference<>();
        AtomicReference<ChatResponse> whole = new AtomicReference<>();
        new MessageAggregator()
                .aggregate(request(conversationId, messages, events, capture).stream().chatResponse(), whole::set)
                .doOnNext(chunk -> {
                    last.set(chunk);
                    String token = chunk.getResult() == null ? null : chunk.getResult().getOutput().getText();
                    if (StringUtils.hasLength(token)) {
                        text.append(token);
                        events.accept(new AgentEvent.Token(token));
                    }
                })
                .blockLast();
        // The keys ride on the last chunk only; the aggregate has the reply's text and reasoning too
        return new Reply(text.toString(), pause(conversationId, last.get(), whole.get(), capture));
    }

    /** The pause a response ended in, or {@code null}. */
    private static PausedTurn pause(String conversationId, ChatResponse last, ChatResponse whole,
                                    ToolOptionsCapture capture) {
        List<String> awaiting = ToolCallDecisions.awaitingDecision(last);
        if (awaiting.isEmpty()) {
            return null;
        }
        if (capture.options() == null) {
            throw new IllegalStateException("The reply paused, but the turn's tools are unknown");
        }
        ChatResponse reply = sameToolCalls(whole, last) ? whole : last;
        return new PausedTurn(conversationId, reply, awaiting, capture.options());
    }

    private static boolean sameToolCalls(ChatResponse whole, ChatResponse last) {
        if (whole == null) {
            return false;
        }
        Optional<AssistantMessage> calls = whole.getResults().stream().map(Generation::getOutput)
                .filter(AssistantMessage::hasToolCalls).findFirst();
        return calls.isPresent()
                && calls.get().getToolCalls().equals(PausedTurn.toolCallMessage(last).getToolCalls());
    }

    /** Every request carries the conversation id, observes its tool calls and records its tools. */
    private ChatClient.ChatClientRequestSpec request(String conversationId, List<Message> messages,
                                                     Consumer<AgentEvent> events, ToolOptionsCapture capture) {
        return chatClient
                .prompt()
                .messages(messages)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                        .advisors(new ToolCallObservingAdvisor(conversationId, events), capture));
    }

    /** Events about tools are reports; a consumer that fails must not stop the turn. */
    private static void emit(Consumer<AgentEvent> events, AgentEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.warn("Event consumer failed for {}", event.getClass().getSimpleName(), e);
        }
    }
}

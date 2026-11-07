package ai.javaclaw.agent;

import ai.javaclaw.approval.ApprovalListener;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.ApprovalTurn;
import ai.javaclaw.approval.HumanQuestionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import java.util.function.Consumer;

@Component
public class DefaultAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgent.class);

    private final ChatClient chatClient;

    public DefaultAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String respondTo(String conversationId, String question) {
        return ask(conversationId, question, _ -> {}, null).call().content();
    }

    @Override
    public void respondTo(String conversationId, String question, Consumer<AgentEvent> events) {
        respondTo(conversationId, question, events, null);
    }

    @Override
    public void respondTo(String conversationId, String question, Consumer<AgentEvent> events,
                          HumanQuestionHandler questions) {
        try {
            ask(conversationId, question, events, questions)
                    .stream()
                    .content()
                    .doOnNext(token -> events.accept(new AgentEvent.Token(token)))
                    .blockLast();
            events.accept(new AgentEvent.Done());
        } catch (UnsupportedOperationException e) {
            // The configured model cannot stream — fall back to the blocking call, still reporting tools
            events.accept(new AgentEvent.Token(ask(conversationId, question, events, questions).call().content()));
            events.accept(new AgentEvent.Done());
        } catch (RuntimeException e) {
            log.warn("Streaming response failed for conversation {}", conversationId, e);
            events.accept(new AgentEvent.Failed(summarizeError(e)));
        }
    }

    @Override
    public String answer(String conversationId, String question, HumanQuestionHandler questions,
                         Consumer<AgentEvent> events) {
        return ask(conversationId, question, events, questions).call().content();
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result) {
        return prompt(conversationId, input, result, _ -> {});
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result, Consumer<AgentEvent> events) {
        return ask(conversationId, input, events, null).call().entity(result);
    }

    /**
     * Every request carries the conversation id, observes its tool calls, and carries the turn's
     * approval state to the tool gate.
     *
     * @param questions who can approve tool calls during this turn, or {@code null} for nobody
     */
    private ChatClient.ChatClientRequestSpec ask(String conversationId, String question,
                                                 Consumer<AgentEvent> events, HumanQuestionHandler questions) {
        ApprovalTurn turn = new ApprovalTurn(conversationId, questions, approvalEvents(events));
        return chatClient
                .prompt(question)
                .toolContext(turn.toolContext())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                        .advisors(new ToolCallObservingAdvisor(conversationId, events)));
    }

    private static ApprovalListener approvalEvents(Consumer<AgentEvent> events) {
        return new ApprovalListener() {
            @Override
            public void requested(ApprovalRequest request) {
                events.accept(new AgentEvent.ApprovalRequested(request));
            }

            @Override
            public void resolved(ApprovalOutcome outcome) {
                events.accept(new AgentEvent.ApprovalResolved(outcome.approvalId(), outcome.toolCallId(),
                        outcome.toolName(), outcome.denial()));
            }
        };
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

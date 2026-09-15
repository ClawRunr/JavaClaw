package ai.javaclaw.agent;

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
        return ask(conversationId, question, _ -> {}).call().content();
    }

    @Override
    public void respondTo(String conversationId, String question, Consumer<AgentEvent> events) {
        try {
            ask(conversationId, question, events)
                    .stream()
                    .content()
                    .doOnNext(token -> events.accept(new AgentEvent.Token(token)))
                    .blockLast();
            events.accept(new AgentEvent.Done());
        } catch (UnsupportedOperationException e) {
            // The configured model cannot stream — fall back to the blocking call
            events.accept(new AgentEvent.Token(respondTo(conversationId, question)));
            events.accept(new AgentEvent.Done());
        } catch (RuntimeException e) {
            log.warn("Streaming response failed for conversation {}", conversationId, e);
            events.accept(new AgentEvent.Failed(summarizeError(e)));
        }
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result) {
        return ask(conversationId, input, _ -> {}).call().entity(result);
    }

    /** Every request carries the conversation id and observes its tool calls. */
    private ChatClient.ChatClientRequestSpec ask(String conversationId, String question,
                                                 Consumer<AgentEvent> events) {
        return chatClient
                .prompt(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                        .advisors(new ToolCallObservingAdvisor(conversationId, events)));
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

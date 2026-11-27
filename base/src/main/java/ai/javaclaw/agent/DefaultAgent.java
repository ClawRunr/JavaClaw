package ai.javaclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
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
    public String respondTo(AgentRequest request) {
        return ask(request, _ -> {}).call().content();
    }

    @Override
    public void respondTo(AgentRequest request, Consumer<AgentEvent> events) {
        try {
            ask(request, events)
                    .stream()
                    .content()
                    .doOnNext(token -> events.accept(new AgentEvent.Token(token)))
                    .blockLast();
            events.accept(new AgentEvent.Done());
        } catch (UnsupportedOperationException e) {
            // The configured model cannot stream — fall back to the blocking call, still reporting tools
            events.accept(new AgentEvent.Token(ask(request, events).call().content()));
            events.accept(new AgentEvent.Done());
        } catch (RuntimeException e) {
            log.warn("Streaming response failed for conversation {}", request.conversationId(), e);
            events.accept(new AgentEvent.Failed(summarizeError(e)));
        }
    }

    @Override
    public <T> T prompt(AgentRequest request, Class<T> result) {
        return ask(request, _ -> {}).call().entity(result);
    }

    /**
     * Every request names its conversation's session, tagging a new one with its kind and channel,
     * and observes its tool calls.
     */
    private ChatClient.ChatClientRequestSpec ask(AgentRequest request, Consumer<AgentEvent> events) {
        return chatClient
                .prompt(request.input())
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, request.conversationId())
                        .param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
                                ChatHistory.sessionMetadata(request.kind(), request.channel()))
                        .advisors(new ToolCallObservingAdvisor(request.conversationId(), events)));
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

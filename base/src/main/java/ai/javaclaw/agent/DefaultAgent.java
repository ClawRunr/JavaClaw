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
    private final ChatSessions chatSessions;
    private final SessionTitles sessionTitles;

    public DefaultAgent(ChatClient chatClient, ChatSessions chatSessions, SessionTitles sessionTitles) {
        this.chatClient = chatClient;
        this.chatSessions = chatSessions;
        this.sessionTitles = sessionTitles;
    }

    @Override
    public String respondTo(AgentRequest request) {
        String sessionId = sessionOf(request);
        String reply = ask(request, sessionId, _ -> {}).call().content();
        afterExchange(request, sessionId);
        return reply;
    }

    @Override
    public void respondTo(AgentRequest request, Consumer<AgentEvent> events) {
        String sessionId = sessionOf(request);
        try {
            ask(request, sessionId, events)
                    .stream()
                    .content()
                    .doOnNext(token -> events.accept(new AgentEvent.Token(token)))
                    .blockLast();
            afterExchange(request, sessionId);
            events.accept(new AgentEvent.Done());
        } catch (UnsupportedOperationException e) {
            // The configured model cannot stream — fall back to the blocking call, still reporting tools
            events.accept(new AgentEvent.Token(ask(request, sessionId, events).call().content()));
            afterExchange(request, sessionId);
            events.accept(new AgentEvent.Done());
        } catch (RuntimeException e) {
            log.warn("Streaming response failed for conversation {}", request.conversation(), e);
            events.accept(new AgentEvent.Failed(summarizeError(e)));
        }
    }

    @Override
    public <T> T prompt(AgentRequest request, Class<T> result) {
        return ask(request, sessionOf(request), _ -> {}).call().entity(result);
    }

    /** The conversation's active session, which the request goes to. */
    private String sessionOf(AgentRequest request) {
        return chatSessions.active(request.conversation(), request.kind(), request.channel());
    }

    /** A chat session gets a title after its first exchange; tasks' sessions are never listed. */
    private void afterExchange(AgentRequest request, String sessionId) {
        if (request.kind() == SessionKind.CHAT) {
            sessionTitles.afterExchange(request.conversation(), sessionId);
        }
    }

    /** Every request names its session and observes its tool calls. */
    private ChatClient.ChatClientRequestSpec ask(AgentRequest request, String sessionId, Consumer<AgentEvent> events) {
        return chatClient
                .prompt(request.input())
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                        .advisors(new ToolCallObservingAdvisor(sessionId, events)));
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

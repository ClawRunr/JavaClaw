package ai.javaclaw.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The conversations as a person reads them, for every chat UI.
 */
@Component
public class ChatHistory {

    /** The {@link SessionAttributes#AGENT_NAME} every JavaClaw session is tagged with. */
    static final String AGENT_NAME = "javaclaw";

    private final SessionService sessionService;

    public ChatHistory(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** Session metadata for a new conversation, so {@link #conversationIds()} finds it. */
    static Map<String, Object> sessionMetadata(SessionKind kind, SessionChannel channel) {
        return Map.of(SessionAttributes.AGENT_NAME, AGENT_NAME,
                SessionKind.ATTRIBUTE, kind.value(),
                SessionChannel.ATTRIBUTE, channel.value());
    }

    /** The ids of all chat conversations, oldest first; scheduled tasks' conversations are left out. */
    public List<String> conversationIds() {
        return sessionService.findByMetadata(SessionAttributes.AGENT_NAME, AGENT_NAME).stream()
                .filter(ChatHistory::isChat)
                .map(Session::id)
                .toList();
    }

    /** A session created before sessions had a kind is a chat, unless its id is a task's. */
    private static boolean isChat(Session session) {
        Object kind = session.metadata().get(SessionKind.ATTRIBUTE);
        return kind == null
                ? !session.id().startsWith(SessionKind.TASK_ID_PREFIX)
                : SessionKind.CHAT.value().equals(kind);
    }

    /**
     * The user messages and the assistant messages with text of a conversation, in order. Tool
     * calls, tool results and assistant messages without text are left out.
     *
     * @return the messages, empty for a conversation that does not exist yet
     */
    public List<Message> messages(String conversationId) {
        return entries(conversationId).stream().map(Entry::message).toList();
    }

    /**
     * The same messages as {@link #messages(String)}, each with the time it was stored.
     *
     * @return the entries, empty for a conversation that does not exist yet
     */
    public List<Entry> entries(String conversationId) {
        return sessionService.getEvents(conversationId).stream()
                .filter(SessionEvent::hasMessage)
                .filter(event -> isShown(event.getMessage()))
                .map(event -> new Entry(event.getMessage(), event.getTimestamp()))
                .toList();
    }

    /** A message of a conversation and the time it was stored. */
    public record Entry(Message message, Instant timestamp) {}

    private static boolean isShown(Message message) {
        return message instanceof UserMessage
                || message instanceof AssistantMessage assistant
                && assistant.getText() != null && !assistant.getText().isBlank();
    }
}

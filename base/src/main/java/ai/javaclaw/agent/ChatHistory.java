package ai.javaclaw.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return sessionService.getEvents(conversationId).stream()
                .filter(SessionEvent::hasMessage)
                .map(SessionEvent::getMessage)
                .filter(ChatHistory::isShown)
                .toList();
    }

    private static boolean isShown(Message message) {
        return message instanceof UserMessage
                || message instanceof AssistantMessage assistant
                && assistant.getText() != null && !assistant.getText().isBlank();
    }

    /**
     * A conversation grouped by turn, in order. A turn is a user message and everything stored after
     * it until the next user message: the tool calls of all its rounds, each with its result, and
     * the assistant text of all its rounds, joined. Messages stored before the first user message
     * form a turn without a question.
     *
     * @return the turns, empty for a conversation that does not exist yet
     */
    public List<Turn> turns(String conversationId) {
        List<Turn> turns = new ArrayList<>();
        TurnBuilder turn = null;
        for (SessionEvent event : sessionService.getEvents(conversationId)) {
            if (!event.hasMessage()) continue;
            Message message = event.getMessage();
            Instant time = event.getTimestamp();
            if (message instanceof UserMessage) {
                if (turn != null) turns.add(turn.build());
                turn = new TurnBuilder(message.getText(), time);
                continue;
            }
            if (turn == null) turn = new TurnBuilder(null, null);
            switch (message) {
                case AssistantMessage assistant -> turn.add(assistant, time);
                case ToolResponseMessage results -> turn.add(results, time);
                default -> { }
            }
        }
        if (turn != null) turns.add(turn.build());
        return turns;
    }

    /**
     * One turn of a conversation.
     *
     * @param question   the user's message; {@code null} for messages stored before the first one
     * @param askedAt    when the question was stored; {@code null} without a question
     * @param toolCalls  every tool call of the turn, in order
     * @param answer     the turn's assistant text, joined in order; empty when there is none
     * @param answeredAt when the first assistant message with text or tool calls was stored;
     *                   {@code null} when the turn has no answer (yet)
     */
    public record Turn(String question, Instant askedAt, List<ToolCall> toolCalls, String answer, Instant answeredAt) {

        /** Whether the turn has assistant text or tool calls to show. */
        public boolean hasAnswer() {
            return answeredAt != null;
        }
    }

    /**
     * A tool call and its result.
     *
     * @param arguments the arguments the model called the tool with
     * @param calledAt  when the call was stored
     * @param result    the stored result for the call's id; {@code null} when there is none
     * @param resultAt  when the result was stored; {@code null} when there is none
     */
    public record ToolCall(String id, String name, String arguments, Instant calledAt, String result, Instant resultAt) {

        ToolCall withResult(String result, Instant resultAt) {
            return new ToolCall(id, name, arguments, calledAt, result, resultAt);
        }
    }

    private static final class TurnBuilder {

        private final String question;
        private final Instant askedAt;
        /**
         * In call order, by tool call id; a call with a blank or repeated id is kept under its
         * position in the turn instead, so it never replaces another call.
         */
        private final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
        private final List<String> texts = new ArrayList<>();
        private Instant answeredAt;

        TurnBuilder(String question, Instant askedAt) {
            this.question = question;
            this.askedAt = askedAt;
        }

        void add(AssistantMessage message, Instant time) {
            boolean hasText = message.getText() != null && !message.getText().isBlank();
            if (!hasText && !message.hasToolCalls()) return;
            if (answeredAt == null) answeredAt = time;
            if (hasText) texts.add(message.getText());
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                ToolCall toolCall = new ToolCall(call.id(), call.name(), call.arguments(), time, null, null);
                boolean hasId = call.id() != null && !call.id().isBlank();
                if (!hasId || toolCalls.putIfAbsent(call.id(), toolCall) != null) {
                    // NUL never appears in a real id, so this key cannot replace a call either
                    toolCalls.put("\0" + toolCalls.size(), toolCall);
                }
            }
        }

        void add(ToolResponseMessage message, Instant time) {
            for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
                toolCalls.computeIfPresent(response.id(), (id, call) -> call.withResult(response.responseData(), time));
            }
        }

        Turn build() {
            return new Turn(question, askedAt, List.copyOf(toolCalls.values()), String.join("\n\n", texts), answeredAt);
        }
    }
}

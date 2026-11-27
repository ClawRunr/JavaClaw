package ai.javaclaw.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sessions as a person reads them, for every chat UI. Which session is which conversation's is
 * {@link ChatSessions}' business.
 */
@Component
public class ChatHistory {

    private final SessionService sessionService;

    public ChatHistory(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * The user messages and the assistant messages with text of a session, in order. Tool calls,
     * tool results and assistant messages without text are left out.
     *
     * @return the messages, empty for a session that does not exist yet
     */
    public List<Message> messages(String sessionId) {
        return sessionService.getEvents(sessionId).stream()
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
     * A session grouped by turn, in order. A turn is a user message and everything stored after
     * it until the next user message: the tool calls of all its rounds, each with its result, and
     * the assistant text of all its rounds, joined. Messages stored before the first user message
     * form a turn without a question.
     *
     * @return the turns, empty for a session that does not exist yet
     */
    public List<Turn> turns(String sessionId) {
        List<Turn> turns = new ArrayList<>();
        TurnBuilder turn = null;
        for (SessionEvent event : sessionService.getEvents(sessionId)) {
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
     * One turn of a session.
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

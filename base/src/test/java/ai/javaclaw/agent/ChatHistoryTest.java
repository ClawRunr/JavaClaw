package ai.javaclaw.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryTest {

    private final SessionService sessions = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .build();

    private final ChatHistory history = new ChatHistory(sessions);

    @Test
    void showsQuestionsAndAnswersButNotToolMessagesOrAssistantMessagesWithoutText() {
        open("web");
        append("web",
                new UserMessage("Weather in Paris?"),
                toolCall("call-1", ""),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "\"sunny\"")))
                        .build(),
                new AssistantMessage("   "),
                new AssistantMessage("It is sunny."));

        assertThat(texts(history.messages("web"))).containsExactly("Weather in Paris?", "It is sunny.");
    }

    @Test
    void anAssistantMessageWithTextIsShownEvenWhenItAlsoCallsATool() {
        open("web");
        append("web", new UserMessage("Weather?"), toolCall("call-1", "Let me check."));

        assertThat(texts(history.messages("web"))).containsExactly("Weather?", "Let me check.");
    }

    @Test
    void aConversationThatDoesNotExistYetIsEmptyAndIsNotCreatedByReadingIt() {
        assertThat(history.messages("web")).isEmpty();
        assertThat(sessions.findById("web")).isNull();
    }

    @Test
    void listsTheChatConversationsTaggedWithTheAgentNameOnly() {
        open("web");
        open("telegram-42");
        sessions.create(CreateSessionRequest.builder().id("task-2026-11-14-090000-report")
                .metadata(ChatHistory.sessionMetadata(SessionKind.TASK, SessionChannel.INTERNAL)).build());
        sessions.create(CreateSessionRequest.builder().id("someone-else").build());

        assertThat(history.conversationIds()).containsExactlyInAnyOrder("web", "telegram-42");
    }

    @Test
    void aSessionWithoutAKindIsAChatUnlessItsIdIsATasks() {
        Map<String, Object> beforeKinds = Map.of(SessionAttributes.AGENT_NAME, ChatHistory.AGENT_NAME);
        sessions.create(CreateSessionRequest.builder().id("discord-7").metadata(beforeKinds).build());
        sessions.create(CreateSessionRequest.builder().id("task-2026-11-13-174009-check").metadata(beforeKinds).build());

        assertThat(history.conversationIds()).containsExactly("discord-7");
    }

    private void open(String conversationId) {
        sessions.create(CreateSessionRequest.builder()
                .id(conversationId)
                .metadata(ChatHistory.sessionMetadata(SessionKind.CHAT, SessionChannel.WEB))
                .build());
    }

    private void append(String conversationId, Message... messages) {
        for (Message message : messages) {
            sessions.appendMessage(conversationId, message);
        }
    }

    private static AssistantMessage toolCall(String id, String text) {
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "weather", "{}")))
                .build();
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }
}

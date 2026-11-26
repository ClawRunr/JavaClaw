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
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ChatHistoryTest {

    private final SessionService sessions = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .build();

    private final ChatHistory history = new ChatHistory(sessions);

    private static final Instant T0 = Instant.parse("2026-11-14T10:42:05Z");

    @Test
    void aTurnWithoutToolsIsTheQuestionAndTheAnswer() {
        open("web");
        at("web", 0, new UserMessage("Hello"));
        at("web", 3, new AssistantMessage("Hi there"));
        at("web", 10, new UserMessage("Bye"));
        at("web", 12, new AssistantMessage("See you"));

        assertThat(history.turns("web")).containsExactly(
                new ChatHistory.Turn("Hello", T0, List.of(), "Hi there", T0.plusSeconds(3)),
                new ChatHistory.Turn("Bye", T0.plusSeconds(10), List.of(), "See you", T0.plusSeconds(12)));
    }

    @Test
    void aTurnWithTwoToolRoundsHasOneToolGroupInOrderAndAllItsText() {
        open("web");
        at("web", 0, new UserMessage("Weather in Paris and Rome?"));
        at("web", 1, toolCalls("Let me check Paris.", call("call-1", "weather", "{\"city\":\"Paris\"}")));
        at("web", 3, results(result("call-1", "weather", "\"sunny\"")));
        at("web", 4, toolCalls("Now Rome.", call("call-2", "weather", "{\"city\":\"Rome\"}")));
        at("web", 6, results(result("call-2", "weather", "\"rainy\"")));
        at("web", 7, new AssistantMessage("Paris is sunny, Rome is rainy."));

        assertThat(history.turns("web")).singleElement().satisfies(turn -> {
            assertThat(turn.question()).isEqualTo("Weather in Paris and Rome?");
            assertThat(turn.answeredAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(turn.answer()).isEqualTo("Let me check Paris.\n\nNow Rome.\n\nParis is sunny, Rome is rainy.");
            assertThat(turn.toolCalls()).containsExactly(
                    new ChatHistory.ToolCall("call-1", "weather", "{\"city\":\"Paris\"}",
                            T0.plusSeconds(1), "\"sunny\"", T0.plusSeconds(3)),
                    new ChatHistory.ToolCall("call-2", "weather", "{\"city\":\"Rome\"}",
                            T0.plusSeconds(4), "\"rainy\"", T0.plusSeconds(6)));
        });
    }

    @Test
    void eachResultIsMatchedToItsCallByIdWhenARoundHasSeveralCalls() {
        open("web");
        at("web", 0, new UserMessage("Compare"));
        at("web", 1, toolCalls("", call("call-a", "weather", "{\"city\":\"Paris\"}"),
                call("call-b", "clock", "{\"city\":\"Rome\"}")));
        // The results come back in another order than the calls
        at("web", 5, results(result("call-b", "clock", "\"10:42\""), result("call-a", "weather", "\"sunny\"")));
        at("web", 6, new AssistantMessage("Done."));

        assertThat(history.turns("web").getFirst().toolCalls())
                .extracting(ChatHistory.ToolCall::id, ChatHistory.ToolCall::name, ChatHistory.ToolCall::result)
                .containsExactly(tuple("call-a", "weather", "\"sunny\""), tuple("call-b", "clock", "\"10:42\""));
    }

    @Test
    void callsWithBlankIdsAreAllShown() {
        open("web");
        at("web", 0, new UserMessage("Weather?"));
        at("web", 1, toolCalls("", call("", "weather", "{\"city\":\"Paris\"}"), call(" ", "weather", "{\"city\":\"Rome\"}")));

        assertThat(history.turns("web").getFirst().toolCalls()).extracting(ChatHistory.ToolCall::arguments)
                .containsExactly("{\"city\":\"Paris\"}", "{\"city\":\"Rome\"}");
    }

    @Test
    void aCallWithoutAStoredResultHasNoResult() {
        open("web");
        at("web", 0, new UserMessage("Weather?"));
        at("web", 1, toolCalls("", call("call-1", "weather", "{}")));

        ChatHistory.Turn turn = history.turns("web").getFirst();
        assertThat(turn.hasAnswer()).isTrue();
        assertThat(turn.answer()).isEmpty();
        assertThat(turn.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.result()).isNull();
            assertThat(call.resultAt()).isNull();
        });
    }

    @Test
    void assistantMessagesWithoutTextOrToolCallsAreLeftOut() {
        open("web");
        at("web", 0, new UserMessage("Hello"));
        at("web", 1, new AssistantMessage("   "));

        assertThat(history.turns("web")).containsExactly(new ChatHistory.Turn("Hello", T0, List.of(), "", null));
        assertThat(history.turns("web").getFirst().hasAnswer()).isFalse();
    }

    @Test
    void messagesAreTheQuestionsAndAnswersWithoutToolMessagesOrAssistantMessagesWithoutText() {
        open("web");
        at("web", 0, new UserMessage("Weather in Paris?"));
        at("web", 1, toolCalls("", call("call-1", "weather", "{}")));
        at("web", 2, results(result("call-1", "weather", "\"sunny\"")));
        at("web", 3, new AssistantMessage("   "));
        at("web", 4, new AssistantMessage("It is sunny."));

        assertThat(history.messages("web")).extracting(Message::getText).containsExactly("Weather in Paris?", "It is sunny.");
    }

    @Test
    void messagesBeforeTheFirstQuestionFormATurnWithoutAQuestion() {
        open("web");
        at("web", 0, new AssistantMessage("Reminder: stand-up"));
        at("web", 5, new UserMessage("Thanks"));

        assertThat(history.turns("web")).extracting(ChatHistory.Turn::question, ChatHistory.Turn::answer)
                .containsExactly(tuple(null, "Reminder: stand-up"), tuple("Thanks", ""));
    }

    @Test
    void aConversationThatDoesNotExistYetIsEmptyAndIsNotCreatedByReadingIt() {
        assertThat(history.turns("web")).isEmpty();
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

    private void at(String conversationId, int seconds, Message message) {
        sessions.appendEvent(SessionEvent.builder().sessionId(conversationId)
                .timestamp(T0.plusSeconds(seconds)).message(message).build());
    }

    private static AssistantMessage toolCalls(String text, AssistantMessage.ToolCall... calls) {
        return AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build();
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static ToolResponseMessage results(ToolResponseMessage.ToolResponse... responses) {
        return ToolResponseMessage.builder().responses(List.of(responses)).build();
    }

    private static ToolResponseMessage.ToolResponse result(String id, String name, String data) {
        return new ToolResponseMessage.ToolResponse(id, name, data);
    }
}

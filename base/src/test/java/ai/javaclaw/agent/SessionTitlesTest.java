package ai.javaclaw.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.SessionAttributes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static ai.javaclaw.agent.SessionChannel.WEB;
import static ai.javaclaw.agent.SessionKind.CHAT;
import static org.assertj.core.api.Assertions.assertThat;

class SessionTitlesTest {

    private static final ConversationKey WEB_CHAT = ConversationKey.web();

    private final TestSessionStore store = new TestSessionStore();
    private final List<Object> published = new ArrayList<>();
    private final TitleModel model = new TitleModel();

    /** Runs the background work on the calling thread, so each test sees its result. */
    private final SessionTitles titles = new SessionTitles(store.chatSessions, () -> model, published::add, Runnable::run);

    // --- The request ---------------------------------------------------------------------------

    @Test
    void theRulesAreTheSystemMessageAndTheConversationIsTheUserMessage() {
        model.answers("Weekend trip to Lisbon");
        String id = sessionWithFirstExchange("Can you help me plan a weekend in Lisbon?", "Sure! When are you going?");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(model.prompts).singleElement().satisfies(prompt -> {
            List<Message> messages = prompt.getInstructions();
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
            assertThat(messages.get(0).getText()).isEqualTo(SessionTitles.SYSTEM_PROMPT)
                    .startsWith("You name chat conversations so they're easy to find later in a list.")
                    .endsWith("hello -> NONE");
            assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
            assertThat(messages.get(1).getText()).isEqualTo("""
                    <user_message>
                    Can you help me plan a weekend in Lisbon?
                    </user_message>

                    <assistant_reply>
                    Sure! When are you going?
                    </assistant_reply>""");
        });
    }

    @Test
    void eachExcerptIsCutTo1000Characters() {
        String request = SessionTitles.titleRequest("q".repeat(1500), "a".repeat(1200));

        assertThat(request).contains("<user_message>\n" + "q".repeat(1000) + "\n</user_message>")
                .contains("<assistant_reply>\n" + "a".repeat(1000) + "\n</assistant_reply>");
    }

    @Test
    void laterAttemptsQuoteTheFirstQuestionAndTheLatestAnswer() {
        model.answers("NONE", "Lisbon weekend plan");
        String id = sessionWithFirstExchange("hi", "Hello! How can I help?");
        titles.afterExchange(WEB_CHAT, id);

        store.service.appendMessage(id, new UserMessage("Plan a weekend in Lisbon"));
        store.service.appendMessage(id, new AssistantMessage("Here is a plan for Lisbon."));
        titles.afterExchange(WEB_CHAT, id);

        assertThat(model.prompts.get(1).getInstructions().get(1).getText())
                .contains("<user_message>\nhi\n</user_message>")
                .contains("<assistant_reply>\nHere is a plan for Lisbon.\n</assistant_reply>");
        assertThat(titleOf(id)).isEqualTo("Lisbon weekend plan");
    }

    // --- Cleaning the reply --------------------------------------------------------------------

    @Test
    void cleansTheModelsReply() {
        assertThat(SessionTitles.cleanModelTitle("Title: \"Spring AI 2.1 changes.\"")).isEqualTo("Spring AI 2.1 changes");
        assertThat(SessionTitles.cleanModelTitle("TITLE: «Daily inbox summary»")).isEqualTo("Daily inbox summary");
        assertThat(SessionTitles.cleanModelTitle("“Lisbon weekend”")).isEqualTo("Lisbon weekend");
        assertThat(SessionTitles.cleanModelTitle("`Gradle build`")).isEqualTo("Gradle build");
        assertThat(SessionTitles.cleanModelTitle("[Gradle build]")).isEqualTo("Gradle build");
        assertThat(SessionTitles.cleanModelTitle("\n\n  Lisbon weekend  \nBecause the user asked")).isEqualTo("Lisbon weekend");
    }

    @Test
    void aLongTitleIsCutAtAWholeWord() {
        String seventy = "Planning a long weekend in Lisbon with museums food and evening walks!!";
        assertThat(seventy).hasSize(71);

        String title = SessionTitles.cleanModelTitle(seventy);

        assertThat(title).isEqualTo("Planning a long weekend in Lisbon with museums").hasSizeLessThanOrEqualTo(50);
        assertThat(seventy).startsWith(title + " ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "NONE", "None.", "\"NONE\"", "", "   ", "\n \n"})
    void noneOrNothingIsNoTitle(String answer) {
        assertThat(SessionTitles.cleanModelTitle(answer)).isNull();
    }

    // --- When to title -------------------------------------------------------------------------

    @Test
    void titlesASessionWithTheModelsCleanedAnswer() {
        model.answers("\"Weekend trip to Lisbon.\"");
        String id = sessionWithFirstExchange("Can you help me plan a weekend in Lisbon?", "Sure!");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isEqualTo("Weekend trip to Lisbon");
        assertThat(published).containsExactly(new SessionTitles.SessionTitled(WEB_CHAT, id, "Weekend trip to Lisbon"));
    }

    @Test
    void aNoneReplyLeavesTheSessionUntitledAndTheNextExchangeTriesAgain() {
        model.answers("NONE", "Lisbon weekend plan");
        String id = sessionWithFirstExchange("hello", "Hi! How can I help?");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isNull();
        assertThat(published).isEmpty();

        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isEqualTo("Lisbon weekend plan");
        assertThat(model.prompts).hasSize(2);
    }

    @Test
    void afterThreeAttemptsWithoutATitleTheFirstLineOfTheFirstMessageIsUsed() {
        model.answers("NONE", "none", "");
        String id = sessionWithFirstExchange("hey there\nhow are you?", "Fine, thanks!");

        titles.afterExchange(WEB_CHAT, id);
        titles.afterExchange(WEB_CHAT, id);
        assertThat(titleOf(id)).isNull();
        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isEqualTo("hey there");
        assertThat(model.prompts).hasSize(3);
        titles.afterExchange(WEB_CHAT, id);
        assertThat(model.prompts).hasSize(3);
    }

    @Test
    void aFailedCallFallsBackToTheFirstLineOfTheFirstMessage() {
        model.failure = new IllegalStateException("rate limited");
        String id = sessionWithFirstExchange("Plan a weekend in Lisbon\nWe like museums", "Sure!");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isEqualTo("Plan a weekend in Lisbon");
    }

    @Test
    void theFallbackIsCutAtAWholeWord() {
        model.failure = new IllegalStateException("down");
        String id = sessionWithFirstExchange("Could you please help me plan a long weekend in Lisbon with my family", "Sure!");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isEqualTo("Could you please help me plan a long weekend in");
    }

    @Test
    void aTitleSetByTheUserIsNeverReplaced() {
        model.answers("Lisbon weekend");
        String renamed = sessionWithFirstExchange("Plan a weekend in Lisbon", "Sure!");
        store.chatSessions.rename(WEB_CHAT, renamed, "My trip");
        String named = store.chatSessions.create(WEB_CHAT, CHAT, WEB, "Holiday");
        store.service.appendMessage(named, new UserMessage("Plan a weekend in Porto"));
        store.service.appendMessage(named, new AssistantMessage("Sure!"));

        titles.afterExchange(WEB_CHAT, renamed);
        titles.afterExchange(WEB_CHAT, named);

        assertThat(titleOf(renamed)).isEqualTo("My trip");
        assertThat(titleOf(named)).isEqualTo("Holiday");
        assertThat(model.prompts).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void theTitleRequestIsNotStoredInAnySession() {
        model.answers("Lisbon weekend");
        String id = sessionWithFirstExchange("Plan a weekend in Lisbon", "Sure!");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(store.service.getMessages(id)).extracting(Message::getText).containsExactly("Plan a weekend in Lisbon", "Sure!");
        assertThat(store.service.findByMetadata(SessionAttributes.AGENT_NAME, ChatSessions.AGENT_NAME)).hasSize(1);
    }

    @Test
    void anAutomaticTitleIsUniqueToo() {
        model.answers("Lisbon weekend");
        store.chatSessions.create(WEB_CHAT, CHAT, WEB, "Lisbon weekend");
        String id = sessionWithFirstExchange("Plan a weekend in Lisbon", "Sure!");

        titles.afterExchange(WEB_CHAT, id);

        assertThat(titleOf(id)).isEqualTo("Lisbon weekend #2");
    }

    @Test
    void aLegacySessionIsTitledWithItsFirstMessageWithoutAskingTheModel() {
        store.service.create(CreateSessionRequest.builder().id("web")
                .metadata(Map.of(SessionAttributes.AGENT_NAME, ChatSessions.AGENT_NAME, SessionKind.ATTRIBUTE, "chat"))
                .build());
        store.service.appendMessage("web", new UserMessage("What did I ask before?"));
        store.service.appendMessage("web", new AssistantMessage("Many things."));

        titles.afterExchange(WEB_CHAT, "web");

        assertThat(titleOf("web")).isEqualTo("What did I ask before?");
        assertThat(model.prompts).isEmpty();
        assertThat(store.chatSessions.list(WEB_CHAT)).extracting(ChatSessions.Summary::id).containsExactly("web");
    }

    private String sessionWithFirstExchange(String question, String answer) {
        String id = store.chatSessions.create(WEB_CHAT, CHAT, WEB, null);
        store.service.appendMessage(id, new UserMessage(question));
        store.service.appendMessage(id, new AssistantMessage(answer));
        return id;
    }

    private String titleOf(String sessionId) {
        return ChatSessions.title(store.service.findById(sessionId));
    }

    /** Answers each prompt with the next of {@link #answers}, or fails with {@link #failure}; records each prompt. */
    static class TitleModel implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();
        private final Deque<String> answers = new ArrayDeque<>();
        RuntimeException failure;

        void answers(String... next) {
            answers.addAll(List.of(next));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answers.isEmpty() ? "NONE" : answers.poll()))));
        }
    }
}

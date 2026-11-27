package ai.javaclaw.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static ai.javaclaw.agent.SessionChannel.TELEGRAM;
import static ai.javaclaw.agent.SessionChannel.WEB;
import static ai.javaclaw.agent.SessionKind.CHAT;
import static org.assertj.core.api.Assertions.assertThat;

class ChatCommandsTest {

    private static final ConversationKey WEB_CHAT = ConversationKey.web();

    private final TestSessionStore store = new TestSessionStore();
    private final ChatCommands commands = new ChatCommands(store.chatSessions);

    @ParameterizedTest
    @ValueSource(strings = {"/new", "  /new  ", "/NEW", "/New"})
    void newStartsANewUntitledSession(String text) {
        String old = store.chatSessions.active(WEB_CHAT, CHAT, WEB);

        Optional<String> reply = commands.handle(WEB_CHAT, WEB, text);

        assertThat(reply).contains("Started a new conversation.");
        String created = store.chatSessions.active(WEB_CHAT, CHAT, WEB);
        assertThat(created).isNotEqualTo(old);
        assertThat(ChatSessions.title(store.service.findById(created))).isNull();
    }

    @Test
    void newWithANameTitlesTheNewSession() {
        Optional<String> reply = commands.handle(WEB_CHAT, WEB, "/new   Trip to Lisbon ");

        assertThat(reply).contains(ChatCommands.NEW_CHAT_REPLY);
        assertThat(store.chatSessions.list(WEB_CHAT)).singleElement()
                .satisfies(session -> assertThat(session.title()).isEqualTo("Trip to Lisbon"));
    }

    @Test
    void theNewSessionIsTheConversationsOwn() {
        ConversationKey telegram = ConversationKey.telegram(42, null);

        commands.handle(telegram, TELEGRAM, "/new");

        assertThat(store.chatSessions.list(telegram)).singleElement()
                .satisfies(session -> assertThat(session.channel()).isEqualTo(TELEGRAM));
        assertThat(store.chatSessions.list(WEB_CHAT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/newer", "hello /new", "new", "/ new", "/news today"})
    void anythingElseIsNotACommand(String text) {
        assertThat(commands.handle(WEB_CHAT, WEB, text)).isEmpty();
        assertThat(store.chatSessions.list(WEB_CHAT)).isEmpty();
    }

    @Test
    void aCommandAddressedToThisBotIsOne() {
        assertThat(commands.handle(WEB_CHAT, TELEGRAM, "/new@JavaClawBot Groceries", () -> "javaclawbot")).isPresent();
        assertThat(store.chatSessions.list(WEB_CHAT)).extracting(ChatSessions.Summary::title).containsExactly("Groceries");
    }

    @Test
    void aCommandAddressedToAnotherBotIsNot() {
        assertThat(commands.handle(WEB_CHAT, TELEGRAM, "/new@OtherBot", () -> "JavaClawBot")).isEmpty();
        assertThat(commands.handle(WEB_CHAT, WEB, "/new@JavaClawBot")).isEmpty();
        assertThat(store.chatSessions.list(WEB_CHAT)).isEmpty();
    }
}

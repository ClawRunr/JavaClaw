package ai.javaclaw.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static ai.javaclaw.agent.SessionChannel.TELEGRAM;
import static ai.javaclaw.agent.SessionChannel.WEB;
import static ai.javaclaw.agent.SessionKind.CHAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ChatSessionsTest {

    private static final ConversationKey WEB_CHAT = ConversationKey.web();
    private static final ConversationKey TELEGRAM_CHAT = ConversationKey.telegram(42, null);

    private final TestSessionStore store = new TestSessionStore();
    private final ChatSessions sessions = store.chatSessions;

    // --- The active session ------------------------------------------------------------------

    @Test
    void theFirstUseOfAConversationCreatesASessionWithAGeneratedIdAndItsMetadata() {
        String id = sessions.active(WEB_CHAT, CHAT, WEB);

        assertThat(id).matches("\\d{8}_\\d{6}_[0-9a-f]{8}");
        Map<String, Object> metadata = store.service.findById(id).metadata();
        assertThat(metadata)
                .containsEntry(SessionAttributes.AGENT_NAME, "javaclaw")
                .containsEntry(SessionKind.ATTRIBUTE, "chat")
                .containsEntry(SessionChannel.ATTRIBUTE, "web")
                .containsEntry(ChatSessions.CONVERSATION, "web")
                .containsKey(ChatSessions.ACTIVATED_AT)
                .doesNotContainKey(ChatSessions.TITLE);
        assertThat(Instant.parse(metadata.get(ChatSessions.ACTIVATED_AT).toString())).isNotNull();
    }

    @Test
    void laterUsesReturnTheSameSession() {
        String first = sessions.active(WEB_CHAT, CHAT, WEB);

        assertThat(sessions.active(WEB_CHAT, CHAT, WEB)).isEqualTo(first);
        assertThat(sessions.list(WEB_CHAT)).hasSize(1);
    }

    @Test
    void eachConversationHasItsOwnSessions() {
        String web = sessions.active(WEB_CHAT, CHAT, WEB);
        String telegram = sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);

        assertThat(telegram).isNotEqualTo(web);
        assertThat(sessions.list(WEB_CHAT)).extracting(ChatSessions.Summary::id).containsExactly(web);
        assertThat(sessions.list(TELEGRAM_CHAT)).extracting(ChatSessions.Summary::id).containsExactly(telegram);
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void createMakesTheNewSessionActiveAndKeepsTheOldOneUnchanged() {
        String old = sessions.active(WEB_CHAT, CHAT, WEB);
        store.service.appendMessage(old, new UserMessage("Hello"));
        Map<String, Object> oldMetadata = store.service.findById(old).metadata();

        String created = sessions.create(WEB_CHAT, CHAT, WEB, null);

        assertThat(created).isNotEqualTo(old);
        assertThat(sessions.active(WEB_CHAT, CHAT, WEB)).isEqualTo(created);
        assertThat(store.service.findById(old).metadata()).isEqualTo(oldMetadata);
        assertThat(store.service.getMessages(old)).extracting(m -> m.getText()).containsExactly("Hello");
        assertThat(sessions.list(WEB_CHAT)).extracting(ChatSessions.Summary::id).containsExactlyInAnyOrder(old, created);
    }

    @Test
    void createWithANameTitlesTheNewSession() {
        String created = sessions.create(WEB_CHAT, CHAT, WEB, "  Holiday   plans ");

        assertThat(sessions.list(WEB_CHAT)).extracting(ChatSessions.Summary::id, ChatSessions.Summary::title)
                .containsExactly(tuple(created, "Holiday plans"));
    }

    // --- switchTo ----------------------------------------------------------------------------

    @Test
    void switchToMakesAnEarlierSessionActiveAgain() {
        String first = sessions.active(WEB_CHAT, CHAT, WEB);
        String second = sessions.create(WEB_CHAT, CHAT, WEB, null);

        sessions.switchTo(WEB_CHAT, first);

        assertThat(sessions.active(WEB_CHAT, CHAT, WEB)).isEqualTo(first);
        sessions.switchTo(WEB_CHAT, second);
        assertThat(sessions.active(WEB_CHAT, CHAT, WEB)).isEqualTo(second);
    }

    @Test
    void switchToRefusesASessionOfAnotherConversation() {
        String web = sessions.active(WEB_CHAT, CHAT, WEB);
        String telegram = sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);

        assertThatThrownBy(() -> sessions.switchTo(WEB_CHAT, telegram)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.switchTo(WEB_CHAT, "no-such-session")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.active(WEB_CHAT, CHAT, WEB)).isEqualTo(web);
        assertThat(sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM)).isEqualTo(telegram);
    }

    // --- rename and unique titles ------------------------------------------------------------

    @Test
    void renameSetsTheTitle() {
        String id = sessions.active(WEB_CHAT, CHAT, WEB);

        sessions.rename(WEB_CHAT, id, "Trip to Lisbon");

        assertThat(store.service.findById(id).metadata()).containsEntry(ChatSessions.TITLE, "Trip to Lisbon");
        assertThat(store.service.findByMetadata(ChatSessions.TITLE, "Trip to Lisbon")).extracting(Session::id).containsExactly(id);
    }

    @Test
    void renameRefusesABlankTitleAndASessionOfAnotherConversation() {
        String web = sessions.active(WEB_CHAT, CHAT, WEB);
        String telegram = sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);

        assertThatThrownBy(() -> sessions.rename(WEB_CHAT, web, "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.rename(WEB_CHAT, telegram, "Mine")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.service.findById(telegram).metadata()).doesNotContainKey(ChatSessions.TITLE);
    }

    @Test
    void duplicateTitlesGetANumber() {
        String first = sessions.create(WEB_CHAT, CHAT, WEB, "Groceries");
        String second = sessions.create(WEB_CHAT, CHAT, WEB, "Groceries");
        String third = sessions.create(WEB_CHAT, CHAT, WEB, null);
        sessions.rename(WEB_CHAT, third, "Groceries");

        assertThat(titleOf(first)).isEqualTo("Groceries");
        assertThat(titleOf(second)).isEqualTo("Groceries #2");
        assertThat(titleOf(third)).isEqualTo("Groceries #3");
    }

    @Test
    void renamingASessionToItsOwnTitleKeepsIt() {
        String id = sessions.create(WEB_CHAT, CHAT, WEB, "Groceries");

        sessions.rename(WEB_CHAT, id, "Groceries");

        assertThat(titleOf(id)).isEqualTo("Groceries");
    }

    @Test
    void titlesAreUniquePerConversation() {
        String web = sessions.create(WEB_CHAT, CHAT, WEB, "Groceries");
        String telegram = sessions.create(TELEGRAM_CHAT, CHAT, TELEGRAM, "Groceries");

        assertThat(titleOf(web)).isEqualTo("Groceries");
        assertThat(titleOf(telegram)).isEqualTo("Groceries");
    }

    // --- list --------------------------------------------------------------------------------

    @Test
    void listShowsLatestActivityFirstWithTitlesAndTheActiveFlag() {
        String older = sessions.create(WEB_CHAT, CHAT, WEB, "Older");
        String untitled = sessions.create(WEB_CHAT, CHAT, WEB, null);
        String newer = sessions.create(WEB_CHAT, CHAT, WEB, "Newer");
        // H2 keeps microseconds; Instant.now() has nanoseconds on Linux
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        append(older, now.plusSeconds(30), new UserMessage("latest message of all"));
        append(untitled, now.plusSeconds(10), new UserMessage("hi"));
        append(newer, now.plusSeconds(20), new UserMessage("hello"));

        assertThat(sessions.list(WEB_CHAT))
                .extracting(ChatSessions.Summary::id, ChatSessions.Summary::title, ChatSessions.Summary::active)
                .containsExactly(
                        tuple(older, "Older", false),
                        tuple(newer, "Newer", true),
                        tuple(untitled, "New chat", false));
        assertThat(sessions.list(WEB_CHAT).getFirst().lastActivity()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void aConversationWithoutSessionsListsNothingAndCreatesNothing() {
        assertThat(sessions.list(WEB_CHAT)).isEmpty();
        assertThat(store.service.findByMetadata(SessionAttributes.AGENT_NAME, "javaclaw")).isEmpty();
    }

    @Test
    void otherConversationsListsTheActiveSessionOfEveryOtherChatConversation() {
        sessions.active(WEB_CHAT, CHAT, WEB);
        String oldTelegram = sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);
        String telegram = sessions.create(TELEGRAM_CHAT, CHAT, TELEGRAM, "Groceries");
        sessions.active(ConversationKey.task("/workspace/tasks/2026-11-24/090000-report.md"), SessionKind.TASK, SessionChannel.INTERNAL);

        assertThat(sessions.otherConversations(WEB_CHAT))
                .extracting(ChatSessions.Summary::id, ChatSessions.Summary::title, ChatSessions.Summary::channel)
                .containsExactly(tuple(telegram, "Groceries", TELEGRAM));
        assertThat(sessions.list(TELEGRAM_CHAT)).extracting(ChatSessions.Summary::id).contains(oldTelegram);
    }

    @Test
    void onlyChatSessionsAreConversationsWithTheOwner() {
        sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);
        sessions.active(ConversationKey.task("/workspace/tasks/recurring/check.md"), SessionKind.TASK, SessionChannel.INTERNAL);
        store.service.create(CreateSessionRequest.builder().id("someone-else").build());

        assertThat(sessions.otherConversations(WEB_CHAT)).extracting(ChatSessions.Summary::channel).containsExactly(TELEGRAM);
    }

    // --- Which conversation a session belongs to ---------------------------------------------

    @Test
    void knowsWhichConversationASessionBelongsTo() {
        String web = sessions.active(WEB_CHAT, CHAT, WEB);
        String telegram = sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);

        assertThat(sessions.isSessionOf(WEB_CHAT, web)).isTrue();
        assertThat(sessions.isSessionOf(WEB_CHAT, telegram)).isFalse();
        assertThat(sessions.isSessionOf(WEB_CHAT, "no-such-session")).isFalse();
        assertThat(sessions.isSessionOf(WEB_CHAT, null)).isFalse();
    }

    @Test
    void summaryOfASessionOfTheConversation() {
        String older = sessions.create(WEB_CHAT, CHAT, WEB, "Older");
        String telegram = sessions.active(TELEGRAM_CHAT, CHAT, TELEGRAM);
        sessions.create(WEB_CHAT, CHAT, WEB, "Newer");

        assertThat(sessions.summary(WEB_CHAT, older))
                .extracting(ChatSessions.Summary::title, ChatSessions.Summary::active)
                .containsExactly("Older", false);
        assertThatThrownBy(() -> sessions.summary(WEB_CHAT, telegram)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Titles as they are kept -------------------------------------------------------------

    @Test
    void cleansTitles() {
        assertThat(ChatSessions.cleanTitle("Title: \"Spring AI 2.1 changes.\"")).isEqualTo("Spring AI 2.1 changes");
        assertThat(ChatSessions.cleanTitle("TITLE: «Daily inbox summary»")).isEqualTo("Daily inbox summary");
        assertThat(ChatSessions.cleanTitle("“Lisbon weekend”")).isEqualTo("Lisbon weekend");
        assertThat(ChatSessions.cleanTitle("`Gradle build`")).isEqualTo("Gradle build");
        assertThat(ChatSessions.cleanTitle("[Gradle build]")).isEqualTo("Gradle build");
        assertThat(ChatSessions.cleanTitle("(\"Gradle build.\")")).isEqualTo("Gradle build");
        assertThat(ChatSessions.cleanTitle("\n\n  Lisbon   weekend  \nBecause the user asked")).isEqualTo("Lisbon weekend");
        assertThat(ChatSessions.cleanTitle("  \n ")).isNull();
        assertThat(ChatSessions.cleanTitle("\"\"")).isNull();
        assertThat(ChatSessions.cleanTitle(null)).isNull();
    }

    @Test
    void bracketsThatBelongToTheTitleStay() {
        assertThat(ChatSessions.cleanTitle("Echoing the text bananas (tab B)")).isEqualTo("Echoing the text bananas (tab B)");
        assertThat(ChatSessions.cleanTitle("(Lisbon) trip (day 2)")).isEqualTo("(Lisbon) trip (day 2)");
        assertThat(ChatSessions.cleanTitle("[draft] Release notes")).isEqualTo("[draft] Release notes");
    }

    @Test
    void aLongTitleIsCutAtAWholeWordWithin50Characters() {
        String seventy = "Planning a long weekend in Lisbon with museums food and evening walks!!";

        assertThat(ChatSessions.cleanTitle(seventy)).isEqualTo("Planning a long weekend in Lisbon with museums");
        assertThat(ChatSessions.cleanTitle("x".repeat(80))).hasSize(50);
    }

    @Test
    void aTitleSetByAPersonIsCleanedTheSameWay() {
        String named = sessions.create(WEB_CHAT, CHAT, WEB, "Title: \"Trip to Lisbon.\"");
        String renamed = sessions.create(WEB_CHAT, CHAT, WEB, null);
        sessions.rename(WEB_CHAT, renamed, "Planning a long weekend in Lisbon with museums food and evening walks");

        assertThat(titleOf(named)).isEqualTo("Trip to Lisbon");
        assertThat(titleOf(renamed)).isEqualTo("Planning a long weekend in Lisbon with museums");
    }

    private String titleOf(String sessionId) {
        return ChatSessions.title(store.service.findById(sessionId));
    }

    private void append(String sessionId, Instant timestamp, Message message) {
        store.service.appendEvent(SessionEvent.builder().sessionId(sessionId).timestamp(timestamp).message(message).build());
    }
}

package ai.javaclaw.chat;

import ai.javaclaw.agent.ChatSessions;
import ai.javaclaw.agent.SessionChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHtmlTest {

    @Test
    void agentMessageHasMascotHeaderAndMarkdownSourceWithoutBubbleOrAvatar() {
        String html = ChatHtml.agentBubble("**Hello**", null);

        assertThat(html)
                .startsWith("<article class=\"ar-msg ar-msg--agent\">")
                .contains("<img class=\"ar-msg__mascot\" src=\"/brand/icon-192.png\" alt=\"\" width=\"26\" height=\"26\"")
                .contains("<span class=\"ar-msg__author\">JavaClaw</span>")
                .contains("<div class=\"ar-md\" data-md>**Hello**</div>")
                .doesNotContain("ar-msg__avatar")
                .doesNotContain(">JC<")
                .doesNotContain("ar-msg__bubble");
    }

    @Test
    void agentMessageEscapesHtml() {
        String html = ChatHtml.agentBubble("<img src=x onerror=alert(1)><script>alert(2)</script>", null);

        assertThat(html)
                .doesNotContain("<img src=x")
                .doesNotContain("<script>")
                .contains("&lt;img src=x onerror=alert(1)&gt;&lt;script&gt;alert(2)&lt;/script&gt;");
    }

    @Test
    void agentErrorIsPlainTextInTheAgentLayout() {
        String html = ChatHtml.agentError("Details: <b>401</b>", null);

        assertThat(html)
                .contains("ar-msg--agent ar-msg--error")
                .contains("<span class=\"ar-msg__author\">JavaClaw</span>")
                .contains("<div class=\"ar-msg__error\">Details: &lt;b&gt;401&lt;/b&gt;</div>")
                // Errors are not Markdown
                .doesNotContain("data-md");
    }

    @Test
    void userMessageIsAnEscapedPlainTextBubble() {
        String html = ChatHtml.userBubble("**not markdown** <b>", null);

        assertThat(html).isEqualTo(
                "<article class=\"ar-msg ar-msg--user\"><div class=\"ar-msg__bubble\">**not markdown** &lt;b&gt;</div></article>");
    }

    @Test
    void userTimeGoesBelowTheBubbleAsUtcIso8601() {
        String html = ChatHtml.userBubble("Hi", Instant.parse("2026-11-14T10:42:05.987Z"));

        assertThat(html).isEqualTo("<article class=\"ar-msg ar-msg--user\"><div class=\"ar-msg__bubble\">Hi</div>"
                + "<time class=\"ar-msg__time\" datetime=\"2026-11-14T10:42:05Z\"></time></article>");
    }

    @Test
    void agentTimeGoesNextToTheAuthor() {
        Instant time = Instant.parse("2026-11-14T10:42:05Z");

        assertThat(ChatHtml.agentBubble("Hi", time)).contains(
                "<span class=\"ar-msg__author\">JavaClaw</span><time class=\"ar-msg__time\" datetime=\"2026-11-14T10:42:05Z\"></time></header>");
        assertThat(ChatHtml.agentError("boom", time)).contains(
                "<span class=\"ar-msg__author\">JavaClaw</span><time class=\"ar-msg__time\" datetime=\"2026-11-14T10:42:05Z\"></time></header>");
        assertThat(ChatHtml.typingDots()).doesNotContain("<time");
    }

    @Test
    void typingIndicatorUsesTheAgentHeader() {
        assertThat(ChatHtml.typingDots())
                .contains("class=\"ar-typing\"")
                .contains("<span class=\"ar-msg__author\">JavaClaw</span>")
                .contains("ar-typing__dots")
                .doesNotContain(">JC<");
    }

    @Test
    void webInputAreaHasLabelledTextareaAndSendButton() {
        String html = ChatHtml.chatInputArea(true, null);

        assertThat(html)
                .contains("<form id=\"chat-form\" class=\"ar-composer\" ws-send")
                .contains("<label for=\"message-input\" class=\"is-sr-only\">Message JavaClaw</label>")
                .contains("<textarea id=\"message-input\"")
                .contains("<button id=\"send-btn\" type=\"submit\" class=\"ar-composer__send\"")
                .contains("aria-label=\"Send\"");
    }

    @Test
    void otherConversationsAreReadOnlyWithEscapedLabel() {
        String html = ChatHtml.chatInputArea(false, "<x>");

        assertThat(html)
                .contains("chat-readonly-notice")
                .contains("&lt;x&gt;")
                .doesNotContain("<textarea");
    }

    @Test
    void sessionListsEscapeTitlesAndIdsAndMarkTheShownSession() {
        Instant time = Instant.parse("2026-11-24T15:30:12Z");
        String html = ChatHtml.sessionLists(
                List.of(new ChatSessions.Summary("s1", "<b>bold</b>", SessionChannel.WEB, time, true),
                        new ChatSessions.Summary("s2", "Other", SessionChannel.WEB, time, false)),
                List.of(new ChatSessions.Summary("\"><script>", "Weather", SessionChannel.DISCORD, time, true)),
                "s2");

        assertThat(html)
                .contains("<h2 class=\"ar-side__heading\">Chats</h2>")
                .contains("&lt;b&gt;bold&lt;/b&gt;")
                .doesNotContain("<b>bold</b>")
                .contains("class=\"ar-side__item is-active\" aria-current=\"true\" ws-send hx-vals='{\"type\": \"switchSession\"}' name=\"sessionId\" value=\"s2\"")
                .contains("<p class=\"ar-side__empty\" id=\"chat-list-empty\" hidden>No chats match.</p>")
                .contains("<h2 class=\"ar-side__heading\">Channels</h2>")
                .contains("value=\"&quot;&gt;&lt;script&gt;\"><span class=\"ar-side__channel\">Discord</span>")
                .doesNotContain("<script>");
    }

    @Test
    void sessionListsLeaveOutChannelsWhenThereAreNone() {
        assertThat(ChatHtml.sessionLists(List.of(), List.of(), null)).doesNotContain("Channels");
    }

    @Test
    void theHeaderOfAWebSessionCanRenameIt() {
        String html = ChatHtml.chatHeader(new ChatSessions.Summary("s1", "Trip \"Lisbon\"", SessionChannel.WEB, Instant.now(), true), true);

        assertThat(html)
                .contains("<span class=\"ar-chat-head__label\">Web</span>")
                .contains("<h1 class=\"ar-chat-head__title\" id=\"chat-title\">Trip &quot;Lisbon&quot;</h1>")
                .contains("<form id=\"rename-form\" class=\"ar-rename\" ws-send hx-boost=\"false\" hx-vals='{\"type\": \"renameSession\"}' hidden>")
                .contains("<input type=\"hidden\" name=\"sessionId\" value=\"s1\">")
                .contains("name=\"title\" value=\"Trip &quot;Lisbon&quot;\"")
                .contains("maxlength=\"60\"")
                .contains(">Save</button>")
                .contains(">Cancel</button>");
    }

    @Test
    void theHeaderOfAnotherChannelHasNoRename() {
        String html = ChatHtml.chatHeader(new ChatSessions.Summary("telegram-42", "Weather", SessionChannel.TELEGRAM, Instant.now(), true), false);

        assertThat(html).contains(">Telegram</span>").contains(">Weather</h1>").doesNotContain("rename");
    }
}

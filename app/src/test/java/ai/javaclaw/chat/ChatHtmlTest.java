package ai.javaclaw.chat;

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
        String html = ChatHtml.chatInputArea("web");

        assertThat(html)
                .contains("<form id=\"chat-form\" class=\"ar-composer\" ws-send")
                .contains("<label for=\"message-input\" class=\"is-sr-only\">Message JavaClaw</label>")
                .contains("<textarea id=\"message-input\"")
                .contains("<button id=\"send-btn\" type=\"submit\" class=\"ar-composer__send\"")
                .contains("aria-label=\"Send\"");
    }

    @Test
    void otherConversationsAreReadOnlyWithEscapedLabel() {
        String html = ChatHtml.chatInputArea("<x>");

        assertThat(html)
                .contains("chat-readonly-notice")
                .contains("&lt;x&gt;")
                .doesNotContain("<textarea");
    }

    @Test
    void conversationSelectorEscapesIdsAndMarksSelection() {
        String html = ChatHtml.conversationSelector(List.of("web", "telegram-42", "\"><script>"), "telegram-42");

        assertThat(html)
                .startsWith("<select id=\"channel-select\" name=\"conversationId\"")
                .contains("<option value=\"web\">Web Chat</option>")
                .contains("<option value=\"telegram-42\" selected>Telegram (42)</option>")
                .doesNotContain("<script>")
                .contains("&quot;&gt;&lt;script&gt;");
    }
}

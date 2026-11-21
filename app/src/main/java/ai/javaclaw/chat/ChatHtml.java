package ai.javaclaw.chat;

import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Chat message HTML fragment helpers.
 *
 * <p>Agent text is sent as escaped plain text inside an {@code .ar-md[data-md]} element. The page's
 * Markdown renderer ({@code /chat/markdown.js}) turns it into sanitized HTML in the browser, the same
 * way it renders a streamed answer, so a message looks the same live and after a reload. Without
 * JavaScript the escaped text still reads as plain text.
 *
 * <p>Message times go out as UTC ISO-8601 in an empty {@code <time class="ar-msg__time">}; the page
 * ({@code /chat/time.js}) writes them out in the browser's locale and time zone. A message without a
 * time (the welcome message) has no such element.
 */
public class ChatHtml {

    private ChatHtml() {}

    public static String agentBubble(String text, Instant time) {
        return agentMessage("ar-msg--agent", time, """
                <div class="ar-md" data-md>%s</div>""".formatted(HtmlUtils.htmlEscape(text)));
    }

    /** An agent message that reports a failure; plain text, never Markdown. */
    public static String agentError(String text, Instant time) {
        return agentMessage("ar-msg--agent ar-msg--error", time, """
                <div class="ar-msg__error">%s</div>""".formatted(HtmlUtils.htmlEscape(text)));
    }

    public static String userBubble(String text, Instant time) {
        return """
                <article class="ar-msg ar-msg--user">\
                <div class="ar-msg__bubble">%s</div>%s\
                </article>""".formatted(HtmlUtils.htmlEscape(text), timeTag(time));
    }

    public static String typingDots() {
        return """
                <div class="ar-typing" role="status" aria-label="JavaClaw is typing">\
                %s\
                <div class="ar-typing__dots"><span></span><span></span><span></span></div>\
                </div>""".formatted(agentHead(null));
    }

    public static String chatInputArea(String conversationId) {
        if ("web".equals(conversationId)) {
            return """
                    <form id="chat-form" class="ar-composer" ws-send hx-boost="false"
                          hx-vals='js:{"type": "userMessage", "conversationId": document.getElementById("channel-select") ? document.getElementById("channel-select").value : "web"}'>
                        <label for="message-input" class="is-sr-only">Message JavaClaw</label>
                        <textarea id="message-input" class="ar-composer__input" name="message" rows="1"
                            placeholder="Message JavaClaw…"
                            autocomplete="off" spellcheck="true" autofocus
                            hx-trigger="keydown[key=='Enter'&&!shiftKey] consume"
                            ws-send></textarea>
                        <button id="send-btn" type="submit" class="ar-composer__send" title="Send (Enter)" aria-label="Send">
                            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                                 stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <path d="M12 19V5M5 12l7-7 7 7"/>
                            </svg>
                        </button>
                    </form>""";
        }
        String label = HtmlUtils.htmlEscape(labelFor(conversationId));
        return """
                <p class="chat-readonly-notice">This is a read-only view of <strong>%s</strong>. \
                Open %s to continue the conversation.</p>""".formatted(label, label);
    }

    public static String conversationSelector(List<String> ids, String selectedId) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <select id="channel-select" name="conversationId" \
                ws-send hx-trigger="change" \
                hx-vals='{"type": "channelChanged"}'>""");
        for (String id : ids) {
            sb.append("<option value=\"").append(HtmlUtils.htmlEscape(id)).append("\"");
            if (id.equals(selectedId)) sb.append(" selected");
            sb.append(">").append(HtmlUtils.htmlEscape(labelFor(id))).append("</option>");
        }
        sb.append("</select>");
        return sb.toString();
    }

    private static String agentMessage(String modifiers, Instant time, String content) {
        return """
                <article class="ar-msg %s">%s<div class="ar-msg__body">%s</div></article>"""
                .formatted(modifiers, agentHead(time), content);
    }

    private static String agentHead(Instant time) {
        return """
                <header class="ar-msg__head">\
                <img class="ar-msg__mascot" src="/brand/icon-192.png" alt="" width="26" height="26" draggable="false">\
                <span class="ar-msg__author">JavaClaw</span>%s\
                </header>""".formatted(timeTag(time));
    }

    /** An empty {@code <time>} for {@code time.js} to fill in; nothing when there is no time. */
    private static String timeTag(Instant time) {
        if (time == null) return "";
        return "<time class=\"ar-msg__time\" datetime=\"" + time.truncatedTo(ChronoUnit.SECONDS) + "\"></time>";
    }

    private static String labelFor(String conversationId) {
        if ("web".equals(conversationId)) return "Web Chat";
        if (conversationId.startsWith("discord-")) return "Discord (" + conversationId.substring("discord-".length()) + ")";
        if (conversationId.startsWith("telegram-")) return "Telegram (" + conversationId.substring("telegram-".length()) + ")";
        return conversationId;
    }
}

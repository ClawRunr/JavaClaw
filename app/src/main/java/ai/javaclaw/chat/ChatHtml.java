package ai.javaclaw.chat;

import ai.javaclaw.agent.ChatSessions;
import ai.javaclaw.agent.SessionChannel;
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
 *
 * <p>Tool calls of a message from history go out as JSON in the message's {@code data-tools}
 * attribute; the page ({@code /chat/chat.js}) builds the tool group from it with the same code that
 * renders live tool calls, so the cards look the same live and after a reload.
 */
public class ChatHtml {

    private ChatHtml() {}

    public static String agentBubble(String text, Instant time) {
        return agentBubble(text, null, time);
    }

    /**
     * An agent message with the tool calls of its turn.
     *
     * @param text      the Markdown source; no text element when empty
     * @param toolsJson the tool calls as a JSON array for {@code chat.js}; {@code null} for none
     */
    public static String agentBubble(String text, String toolsJson, Instant time) {
        String content = text.isEmpty() ? "" : """
                <div class="ar-md" data-md>%s</div>""".formatted(HtmlUtils.htmlEscape(text));
        String attributes = toolsJson == null ? "" : " data-tools=\"" + HtmlUtils.htmlEscape(toolsJson) + "\"";
        return agentMessage("ar-msg--agent", attributes, time, content);
    }

    /** An agent message that reports a failure; plain text, never Markdown. */
    public static String agentError(String text, Instant time) {
        return agentMessage("ar-msg--agent ar-msg--error", "", time, """
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

    /** The composer for the web chat; a read-only notice for another channel's conversation. */
    public static String chatInputArea(boolean writable, String label) {
        if (writable) {
            return """
                    <form id="chat-form" class="ar-composer" ws-send hx-boost="false"
                          hx-vals='js:{"type": "userMessage", "sessionId": document.getElementById("chat-messages").getAttribute("data-session-id")}'>
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
        String escaped = HtmlUtils.htmlEscape(label);
        return """
                <p class="chat-readonly-notice">This is a read-only view of <strong>%s</strong>. \
                Open %s to continue the conversation.</p>""".formatted(escaped, escaped);
    }

    /**
     * The header of the shown session: its title and channel and, for a web chat, a rename button
     * that turns the title into a text field with Save and Cancel ({@code /chat/chat.js}).
     */
    public static String chatHeader(ChatSessions.Summary session, boolean renamable) {
        String title = HtmlUtils.htmlEscape(session.title());
        StringBuilder html = new StringBuilder("""
                <span class="ar-chat-head__label">%s</span>\
                <h1 class="ar-chat-head__title" id="chat-title">%s</h1>""".formatted(
                HtmlUtils.htmlEscape(channelLabel(session.channel())), title));
        if (renamable) {
            html.append("""
                    <button type="button" class="ar-chat-head__rename" id="rename-btn" title="Rename" aria-label="Rename chat">\
                    <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" \
                    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/>\
                    <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg></button>\
                    <form id="rename-form" class="ar-rename" ws-send hx-boost="false" hx-vals='{"type": "renameSession"}' hidden>\
                    <input type="hidden" name="sessionId" value="%s">\
                    <label for="rename-input" class="is-sr-only">Chat title</label>\
                    <input id="rename-input" class="ar-rename__input" name="title" value="%s" data-title="%s" \
                    maxlength="%d" required autocomplete="off">\
                    <button type="submit" class="ar-rename__button ar-rename__button--save">Save</button>\
                    <button type="button" class="ar-rename__button" id="rename-cancel">Cancel</button>\
                    </form>""".formatted(HtmlUtils.htmlEscape(session.id()), title, title, ChatSessions.MAX_TITLE_LENGTH));
        }
        return html.toString();
    }

    /**
     * The sidebar's lists: the web chat's sessions, each switching to it, and the other channels'
     * conversations, each opening the read-only view of its active session.
     *
     * @param shownId the session the page shows, highlighted
     */
    public static String sessionLists(List<ChatSessions.Summary> chats, List<ChatSessions.Summary> channels, String shownId) {
        StringBuilder html = new StringBuilder("""
                <h2 class="ar-side__heading">Chats</h2><ul class="ar-side__list" id="chat-list">""");
        for (ChatSessions.Summary chat : chats) {
            html.append(sessionItem("switchSession", chat, null, chat.id().equals(shownId)));
        }
        html.append("""
                </ul><p class="ar-side__empty" id="chat-list-empty" hidden>No chats match.</p>""");
        if (!channels.isEmpty()) {
            html.append("""
                    <h2 class="ar-side__heading">Channels</h2><ul class="ar-side__list">""");
            for (ChatSessions.Summary channel : channels) {
                html.append(sessionItem("viewSession", channel, channelLabel(channel.channel()), channel.id().equals(shownId)));
            }
            html.append("</ul>");
        }
        return html.toString();
    }

    private static String sessionItem(String frameType, ChatSessions.Summary session, String label, boolean shown) {
        return """
                <li><button type="button" class="ar-side__item%s"%s ws-send hx-vals='{"type": "%s"}' \
                name="sessionId" value="%s">%s<span class="ar-side__title">%s</span>%s</button></li>""".formatted(
                shown ? " is-active" : "",
                shown ? " aria-current=\"true\"" : "",
                frameType,
                HtmlUtils.htmlEscape(session.id()),
                label == null ? "" : "<span class=\"ar-side__channel\">" + HtmlUtils.htmlEscape(label) + "</span>",
                HtmlUtils.htmlEscape(session.title()),
                timeTag(session.lastActivity()));
    }

    /** What a channel is called in the chat. */
    public static String channelLabel(SessionChannel channel) {
        if (channel == null) return "Chat";
        return switch (channel) {
            case WEB -> "Web";
            case TELEGRAM -> "Telegram";
            case DISCORD -> "Discord";
            case WHATSAPP -> "WhatsApp";
            case TEAMS -> "Teams";
            case INTERNAL -> "JavaClaw";
        };
    }

    private static String agentMessage(String modifiers, String attributes, Instant time, String content) {
        return """
                <article class="ar-msg %s"%s>%s<div class="ar-msg__body">%s</div></article>"""
                .formatted(modifiers, attributes, agentHead(time), content);
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
}

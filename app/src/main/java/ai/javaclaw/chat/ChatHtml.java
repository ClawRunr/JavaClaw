package ai.javaclaw.chat;

import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * Chat message bubble HTML fragment helpers.
 */
public class ChatHtml {

    private ChatHtml() {}

    public record ToolStep(String name, String arguments, String result) {}

    /**
     * Renders the full agent turn: a collapsible thinking row (if tool steps exist)
     * immediately followed by the response bubble.
     */
    public static String agentTurn(String text, List<ToolStep> steps) {
        if (steps.isEmpty()) return agentBubble(text);
        return thinkingRow(steps) + agentBubble(text);
    }

    private static String thinkingRow(List<ToolStep> steps) {
        String label = steps.size() == 1 ? "Used 1 tool" : "Used " + steps.size() + " tools";
        StringBuilder stepsHtml = new StringBuilder();
        for (ToolStep step : steps) {
            String resultBlock = step.result() != null && !step.result().isBlank()
                    ? "\n<pre class=\"ar-thinking__step-result\">%s</pre>".formatted(HtmlUtils.htmlEscape(step.result()))
                    : "";
            stepsHtml.append("""
                    <details class="ar-thinking__step" open>
                        <summary class="ar-thinking__step-summary">&#128295; %s</summary>
                        <pre class="ar-thinking__step-args">%s</pre>%s
                    </details>""".formatted(
                    HtmlUtils.htmlEscape(step.name()),
                    HtmlUtils.htmlEscape(step.arguments()),
                    resultBlock));
        }
        return """
                <div class="ar-thinking">
                    <details class="ar-thinking__body">
                        <summary class="ar-thinking__toggle">%s</summary>
                        <div class="ar-thinking__steps">%s</div>
                    </details>
                </div>""".formatted(label, stepsHtml);
    }

    public static String agentBubble(String text) {
        return """
                <article class="ar-msg ar-msg--agent">\
                <div class="ar-msg__avatar">JC</div>\
                <div class="ar-msg__bubble">%s</div>\
                </article>""".formatted(text != null && !text.isBlank() ? HtmlUtils.htmlEscape(text) : "");
    }

    public static String userBubble(String text) {
        return """
                <article class="ar-msg ar-msg--user">\
                <div class="ar-msg__bubble">%s</div>\
                </article>""".formatted(HtmlUtils.htmlEscape(text));
    }

    public static String typingDots() {
        return """
                <div class="ar-typing">\
                <div class="ar-msg__avatar">JC</div>\
                <div class="ar-typing__dots"><span></span><span></span><span></span></div>\
                </div>""";
    }

    public static String chatInputArea(String conversationId) {
        if ("web".equals(conversationId)) {
            return """
                    <form id="chat-form" ws-send hx-boost="false"
                          hx-vals='js:{"type": "userMessage", "conversationId": document.getElementById("channel-select") ? document.getElementById("channel-select").value : "web"}'>
                        <div class="field is-grouped" style="align-items: flex-end; margin: 0;">
                            <div class="control is-expanded">
                                <textarea id="message-input" class="textarea" name="message" rows="1"
                                    placeholder="Message JavaClaw\u2026"
                                    autocomplete="off" spellcheck="true" autofocus
                                    hx-trigger="keydown[key=='Enter'&&!shiftKey] consume"
                                    ws-send></textarea>
                            </div>
                            <div class="control">
                                <button id="send-btn" type="submit" class="button is-primary" title="Send (Enter)">
                                    <span class="icon">
                                        <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
                                            <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
                                        </svg>
                                    </span>
                                </button>
                            </div>
                        </div>
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
                <select id="channel-select" class="select" name="conversationId" \
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

    private static String labelFor(String conversationId) {
        if ("web".equals(conversationId)) return "Web Chat";
        if (conversationId.startsWith("discord-")) return "Discord (" + conversationId.substring("discord-".length()) + ")";
        if (conversationId.startsWith("telegram-")) return "Telegram (" + conversationId.substring("telegram-".length()) + ")";
        return conversationId;
    }
}

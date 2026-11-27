package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ChatCommands;
import ai.javaclaw.agent.ChatHistory;
import ai.javaclaw.agent.ChatSessions;
import ai.javaclaw.agent.ConversationKey;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.agent.SessionKind;
import ai.javaclaw.agent.SessionTitles;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GUI channel for the web chat interface, in any number of browser tabs.
 *
 * <p>Each tab (WebSocket session) shows one chat session of its own: a session of the web
 * conversation, which it continues, or another channel's conversation, read-only. A new tab opens
 * the web conversation's active session. Every view sends the header, the sidebar, the messages
 * and the composer together, and marks the messages with the shown session's id.
 *
 * <p>An answer is streamed as JSON frames that carry the session id of the turn and go only to the
 * tabs showing that session. Background messages go to every tab, or are buffered for REST polling
 * while no tab is open.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);

    /** The longest tool output a history message carries; live tool results are not cut. */
    static final int MAX_HISTORY_TOOL_OUTPUT = 20_000;
    /** The web chat's conversation, shared with the Vaadin chat at {@code /ui}. */
    static final ConversationKey WEB = ConversationKey.web();

    /** Shown in a session without messages. */
    static final String WELCOME = "Hi! I'm your JavaClaw assistant. How can I help you today?";

    private final Agent agent;
    private final ChannelRegistry channelRegistry;
    private final ChatSessions chatSessions;
    private final ChatCommands chatCommands;
    private final ChatHistory chatHistory;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    /** Every open tab, by its WebSocket session's id. */
    private final Map<String, Tab> tabs = new ConcurrentHashMap<>();

    public ChatChannel(Agent agent, ChannelRegistry channelRegistry, ChatSessions chatSessions,
                       ChatCommands chatCommands, ChatHistory chatHistory, ObjectMapper objectMapper) {
        this.agent = agent;
        this.channelRegistry = channelRegistry;
        this.chatSessions = chatSessions;
        this.chatCommands = chatCommands;
        this.chatHistory = chatHistory;
        this.objectMapper = objectMapper;
        channelRegistry.registerChannel(this);
        log.info("Started Web Chat channel");
    }

    /**
     * One browser tab: its WebSocket session and the chat session it shows.
     *
     * @param sessionId the shown chat session; null until the first view is sent
     * @param readOnly  whether that is another channel's conversation
     */
    private record Tab(WebSocketSession socket, String sessionId, boolean readOnly) {

        Tab showing(String sessionId, boolean readOnly) {
            return new Tab(socket, sessionId, readOnly);
        }
    }

    @Override
    public String getName() {
        return "Web Chat Channel";
    }

    // --- Tabs ----------------------------------------------------------------------------------

    /** A tab connected: it shows the web conversation's active session. */
    public void open(WebSocketSession socket) throws IOException {
        tabs.put(socket.getId(), new Tab(socket, null, false));
        showWebSession(socket, activeWebSession());
        flushPendingMessages();
    }

    /** A tab disconnected. */
    public void close(WebSocketSession socket) {
        tabs.remove(socket.getId());
    }

    /** Whether the session is one of the web conversation's, which a tab may continue. */
    public boolean isWebSession(String sessionId) {
        return chatSessions.isSessionOf(WEB, sessionId);
    }

    /** Sends a raw HTML fragment to one tab, such as the echo of the question it sent. */
    public void sendHtml(WebSocketSession socket, String... html) throws IOException {
        send(socket, String.join(System.lineSeparator(), html));
    }

    /** Sends a raw HTML fragment to every tab that shows the session, such as the echo of a question. */
    public void sendHtmlTo(String sessionId, String... html) {
        String payload = String.join(System.lineSeparator(), html);
        for (Tab tab : tabs.values()) {
            if (!sessionId.equals(tab.sessionId())) {
                continue;
            }
            try {
                send(tab.socket(), payload);
            } catch (IOException e) {
                log.warn("WS push failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Delivers a background-task message to every open tab, or buffers it for REST polling when
     * no tab is open.
     */
    @Override
    public void sendMessage(String message) {
        String html = Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(message, Instant.now()));
        boolean delivered = false;
        for (Tab tab : tabs.values()) {
            try {
                send(tab.socket(), html);
                delivered = true;
            } catch (IOException e) {
                log.warn("WS push failed: {}", e.getMessage());
            }
        }
        if (!delivered) {
            pendingMessages.add(message);
        }
    }

    /**
     * Delivers messages buffered while no tab was open.
     * Each buffered message is attempted once; a failed push re-buffers it.
     */
    public void flushPendingMessages() {
        for (int i = pendingMessages.size(); i > 0; i--) {
            String message = pendingMessages.poll();
            if (message == null) break;
            sendMessage(message);
        }
    }

    // --- Views ---------------------------------------------------------------------------------

    /**
     * Shows a session of the web conversation in one tab, which then continues it.
     *
     * @param notices agent messages shown below the history that are not part of it, such as the
     *                reply to a command
     * @throws IllegalArgumentException if it is not a session of the web conversation
     */
    public void showWebSession(WebSocketSession socket, String sessionId, String... notices) throws IOException {
        ChatSessions.Summary session = chatSessions.summary(WEB, sessionId);
        List<String> bubbles = new ArrayList<>(notices.length == 0 ? loadHistoryAsHtml(sessionId) : history(sessionId));
        for (String notice : notices) {
            bubbles.add(ChatHtml.agentBubble(notice, Instant.now()));
        }
        Tab tab = show(socket, sessionId, false);
        sendView(tab, ChatHtml.chatHeader(session, true), bubbles, ChatHtml.chatInputArea(true, null));
    }

    /** Shows another channel's conversation in one tab, read-only, by the id of its active session. */
    public void showChannel(WebSocketSession socket, String sessionId) throws IOException {
        ChatSessions.Summary session = chatSessions.summary(sessionId);
        String label = ChatHtml.channelLabel(session.channel()) + " · " + session.title();
        Tab tab = show(socket, sessionId, true);
        sendView(tab, ChatHtml.chatHeader(session, false), loadHistoryAsHtml(sessionId), ChatHtml.chatInputArea(false, label));
    }

    /** Starts a new web chat session, which becomes active, and shows it in this tab. */
    public void newChat(WebSocketSession socket) throws IOException {
        String sessionId = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        showWebSession(socket, sessionId);
        refreshSidebars();
    }

    /**
     * Makes an earlier web chat session active and shows it in this tab.
     *
     * @throws IllegalArgumentException if it is not a web chat session
     */
    public void switchTo(WebSocketSession socket, String sessionId) throws IOException {
        chatSessions.switchTo(WEB, sessionId);
        showWebSession(socket, sessionId);
        refreshSidebars();
    }

    /**
     * Renames a web chat session; the header and the sidebar of every tab follow.
     *
     * @throws IllegalArgumentException if the title is blank or it is not a web chat session
     */
    public void rename(String sessionId, String title) throws IOException {
        chatSessions.rename(WEB, sessionId, title);
        refreshSidebars();
    }

    /**
     * Runs a chat command ({@link ChatCommands}) typed in a tab, and shows the result there; other
     * tabs keep what they show.
     *
     * @return whether {@code message} was a command; if not, it is for the agent
     */
    public boolean runCommand(WebSocketSession socket, String message) throws IOException {
        Optional<String> reply = chatCommands.handle(WEB, SessionChannel.WEB, message);
        if (reply.isEmpty()) {
            return false;
        }
        // The command made its new session the active one
        showWebSession(socket, activeWebSession(), reply.get());
        refreshSidebars();
        return true;
    }

    /** A session got its title in the background: the sidebar and the header of every tab show it. */
    @EventListener
    void onSessionTitled(SessionTitles.SessionTitled event) {
        refreshSidebars();
    }

    /** Sends every tab the header and the sidebar of what it shows, leaving its messages alone. */
    private void refreshSidebars() {
        for (Tab tab : tabs.values()) {
            if (tab.sessionId() == null) {
                continue;
            }
            try {
                String header = tab.readOnly()
                        ? ChatHtml.chatHeader(chatSessions.summary(tab.sessionId()), false)
                        : ChatHtml.chatHeader(chatSessions.summary(WEB, tab.sessionId()), true);
                sendHtml(tab.socket(),
                        Htmx.oobInnerHtml("chat-header", header),
                        Htmx.oobInnerHtml("chat-sessions", sessionLists(tab.sessionId())));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not refresh the sidebar of tab {}: {}", tab.socket().getId(), e.getMessage());
            }
        }
    }

    private Tab show(WebSocketSession socket, String sessionId, boolean readOnly) {
        return tabs.compute(socket.getId(), (_, tab) -> tab == null
                ? new Tab(socket, sessionId, readOnly)
                : tab.showing(sessionId, readOnly));
    }

    /** The messages carry the shown session's id, so the page ignores stream frames of any other. */
    private void sendView(Tab tab, String header, List<String> bubbles, String inputArea) throws IOException {
        sendHtml(tab.socket(),
                Htmx.oobInnerHtml("chat-header", header),
                Htmx.oobInnerHtml("chat-sessions", sessionLists(tab.sessionId())),
                "<div id=\"chat-messages\" data-session-id=\"" + HtmlUtils.htmlEscape(tab.sessionId())
                        + "\" hx-swap-oob=\"true\">" + String.join(System.lineSeparator(), bubbles) + "</div>",
                Htmx.oobReplace("typing-indicator", ""),
                Htmx.oobInnerHtml("chat-input-area", inputArea));
    }

    private String sessionLists(String shownId) {
        return ChatHtml.sessionLists(chatSessions.list(WEB), chatSessions.otherConversations(WEB), shownId);
    }

    private String activeWebSession() {
        return chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
    }

    /**
     * Loads a session's history as HTML messages, one turn at a time: the user message, then one
     * agent message with the turn's tool calls and all its text, the way a live answer looks.
     * Returns a single welcome bubble if the session has no messages yet.
     */
    public List<String> loadHistoryAsHtml(String sessionId) {
        List<String> bubbles = history(sessionId);
        return bubbles.isEmpty() ? List.of(ChatHtml.agentBubble(WELCOME, null)) : bubbles;
    }

    private List<String> history(String sessionId) {
        List<String> bubbles = new ArrayList<>();
        for (ChatHistory.Turn turn : chatHistory.turns(sessionId)) {
            if (turn.question() != null) {
                bubbles.add(ChatHtml.userBubble(turn.question(), turn.askedAt()));
            }
            if (turn.hasAnswer()) {
                bubbles.add(ChatHtml.agentBubble(turn.answer(), toolsJson(turn.toolCalls()), turn.answeredAt()));
            }
        }
        return bubbles;
    }

    // --- Answers -------------------------------------------------------------------------------

    /**
     * The tool calls as {@code chat.js} renders them: the fields of a live {@code toolCall} and
     * {@code toolResult} frame, plus the times of the call and the result. A call without a result
     * has neither {@code output} nor {@code resultAt}. An output longer than
     * {@value #MAX_HISTORY_TOOL_OUTPUT} characters is cut to that length and marked
     * {@code "truncated": true}. {@code null} for no calls.
     */
    private String toolsJson(List<ChatHistory.ToolCall> toolCalls) {
        if (toolCalls.isEmpty()) return null;
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ChatHistory.ToolCall call : toolCalls) {
            Map<String, Object> tool = toolData(call.id(), call.name(), "input", call.arguments());
            tool.put("calledAt", call.calledAt().toString());
            if (call.resultAt() != null) {
                String output = call.result() == null ? "" : call.result();
                if (output.length() > MAX_HISTORY_TOOL_OUTPUT) {
                    // Don't split a surrogate pair
                    int end = Character.isHighSurrogate(output.charAt(MAX_HISTORY_TOOL_OUTPUT - 1))
                            ? MAX_HISTORY_TOOL_OUTPUT - 1 : MAX_HISTORY_TOOL_OUTPUT;
                    output = output.substring(0, end);
                    tool.put("truncated", true);
                }
                tool.put("output", output);
                tool.put("resultAt", call.resultAt().toString());
            }
            tools.add(tool);
        }
        return objectMapper.writeValueAsString(tools);
    }

    /**
     * Handles a chat message sent from a tab: it goes to the web chat session that tab shows, not
     * necessarily the active one. The response is streamed as JSON frames
     * ({@code chunk}/{@code toolCall}/{@code toolResult}/{@code done}/{@code error}), each with the
     * session id and the time it was sent, to the tabs showing that session; the page shows the
     * first frame's time as the time of the answer.
     *
     * @throws IllegalArgumentException if the session is not one of the web conversation's
     */
    public void chat(String sessionId, String message) {
        if (!isWebSession(sessionId)) {
            throw new IllegalArgumentException("Session " + sessionId + " is not one of the web chat's");
        }
        channelRegistry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent(getName(), message));

        agent.respondTo(AgentRequest.chat(WEB, sessionId, SessionChannel.WEB, message), event -> sendFrame(sessionId, switch (event) {
            case AgentEvent.Token t -> frame(StreamFrameType.CHUNK, sessionId, t.text());
            case AgentEvent.ToolCall c -> frame(StreamFrameType.TOOL_CALL, sessionId,
                    toolData(c.id(), c.name(), "input", c.input()));
            case AgentEvent.ToolResult r -> frame(StreamFrameType.TOOL_RESULT, sessionId,
                    toolData(r.id(), r.name(), "output", r.output()));
            case AgentEvent.Done ignored -> frame(StreamFrameType.DONE, sessionId, null);
            case AgentEvent.Failed f -> frame(StreamFrameType.ERROR, sessionId,
                    f.message() == null ? "Unknown error" : f.message());
        }));
    }

    /** Reports a turn that failed before it could stream, to the tabs showing its session. */
    public void failed(String sessionId, String message) {
        sendFrame(sessionId, frame(StreamFrameType.ERROR, sessionId, message));
    }

    private static Map<String, Object> toolData(String id, String name, String payloadKey, String payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put(payloadKey, payload == null ? "" : payload);
        return data;
    }

    private static Map<String, Object> frame(StreamFrameType type, String sessionId, Object payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type.type());
        if (payload != null) frame.put("data", payload);
        frame.put("sessionId", sessionId);
        frame.put("time", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return frame;
    }

    /** Sends a stream frame to the tabs that show its session now; the others never see it. */
    private void sendFrame(String sessionId, Map<String, Object> frame) {
        String json = objectMapper.writeValueAsString(frame);
        for (Tab tab : tabs.values()) {
            if (!sessionId.equals(tab.sessionId())) {
                continue;
            }
            try {
                send(tab.socket(), json);
            } catch (IOException e) {
                log.warn("WS push failed, dropping stream frame: {}", e.getMessage());
            }
        }
    }

    /** A WebSocket session takes one message at a time; streaming, titles and tasks all send. */
    private static void send(WebSocketSession socket, String payload) throws IOException {
        synchronized (socket) {
            if (socket.isOpen()) {
                socket.sendMessage(new TextMessage(payload));
            }
        }
    }
}

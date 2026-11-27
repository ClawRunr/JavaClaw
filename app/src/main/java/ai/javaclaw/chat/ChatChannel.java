package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ChatHistory;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI channel for the web chat interface.
 * Pushes messages directly to the active WebSocket session when connected,
 * falling back to an in-memory queue for REST polling.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);

    /** The longest tool output a history message carries; live tool results are not cut. */
    static final int MAX_HISTORY_TOOL_OUTPUT = 20_000;

    private final Agent agent;
    private final ChannelRegistry channelRegistry;
    private final ChatHistory chatHistory;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final AtomicReference<WebSocketSession> wsSession = new AtomicReference<>();

    public ChatChannel(Agent agent, ChannelRegistry channelRegistry, ChatHistory chatHistory, ObjectMapper objectMapper) {
        this.agent = agent;
        this.channelRegistry = channelRegistry;
        this.chatHistory = chatHistory;
        this.objectMapper = objectMapper;
        channelRegistry.registerChannel(this);
        log.info("Started Web Chat channel");
    }

    @Override
    public String getName() {
        return "Web Chat Channel";
    }

    /**
     * Called by the WebSocket handler when a client connects.
     */
    public void setWsSession(WebSocketSession session) {
        wsSession.set(session);
    }

    /**
     * Called by the WebSocket handler when the client disconnects.
     */
    public void clearWsSession(WebSocketSession session) {
        wsSession.compareAndSet(session, null);
    }

    /**
     * Sends a raw HTML fragment to the active WebSocket session.
     * Used by the WebSocket handler to push user/agent bubbles and typing indicators.
     */
    public void sendHtml(String... html) throws IOException {
        WebSocketSession session = wsSession.get();
        if (session != null && session.isOpen()) {
            session.sendMessage(new TextMessage(String.join(System.lineSeparator(), html)));
        }
    }

    /**
     * Delivers a background-task message. Pushes directly to WebSocket if a session
     * is open, otherwise buffers for REST polling.
     */
    @Override
    public void sendMessage(String message) {
        try {
            sendHtml(buildBackgroundMessageHtml(message));
        } catch (IOException e) {
            log.warn("WS push failed, buffering message: {}", e.getMessage());
            pendingMessages.add(message);
        }
    }

    /**
     * Delivers messages buffered while no WebSocket session was active.
     * Each buffered message is attempted once; a failed push re-buffers it.
     */
    public void flushPendingMessages() {
        for (int i = pendingMessages.size(); i > 0; i--) {
            String message = pendingMessages.poll();
            if (message == null) break;
            sendMessage(message);
        }
    }

    /**
     * Returns all known conversation IDs, always with "web" first.
     */
    public List<String> conversationIds() {
        List<String> result = new ArrayList<>();
        result.add("web");
        chatHistory.conversationIds().stream()
                .filter(id -> !id.equals("web"))
                .forEach(result::add);
        return result;
    }

    /**
     * Loads conversation history for the given conversationId as HTML messages, one turn at a time:
     * the user message, then one agent message with the turn's tool calls and all its text, the way
     * a live answer looks. Returns a single welcome bubble if no history exists yet.
     */
    public List<String> loadHistoryAsHtml(String conversationId) {
        List<ChatHistory.Turn> turns = chatHistory.turns(conversationId);
        if (turns.isEmpty()) {
            return List.of(ChatHtml.agentBubble("Hi! I'm your JavaClaw assistant. How can I help you today?", null));
        }
        List<String> bubbles = new ArrayList<>();
        for (ChatHistory.Turn turn : turns) {
            if (turn.question() != null) {
                bubbles.add(ChatHtml.userBubble(turn.question(), turn.askedAt()));
            }
            if (turn.hasAnswer()) {
                bubbles.add(ChatHtml.agentBubble(turn.answer(), toolsJson(turn.toolCalls()), turn.answeredAt()));
            }
        }
        return bubbles;
    }

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
     * Handles a chat message from the web UI for the given conversationId.
     * The response is streamed to the WebSocket session as JSON frames
     * ({@code chunk}/{@code toolCall}/{@code toolResult}/{@code done}/{@code error}), each with the
     * time it was sent; the page shows the first frame's time as the time of the answer.
     */
    public void chat(String conversationId, String message) {
        channelRegistry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent(getName(), message));

        agent.respondTo(AgentRequest.chat(conversationId, SessionChannel.WEB, message), event -> sendFrame(switch (event) {
            case AgentEvent.Token t -> frame(StreamFrameType.CHUNK, conversationId, t.text());
            case AgentEvent.ToolCall c -> frame(StreamFrameType.TOOL_CALL, conversationId,
                    toolData(c.id(), c.name(), "input", c.input()));
            case AgentEvent.ToolResult r -> frame(StreamFrameType.TOOL_RESULT, conversationId,
                    toolData(r.id(), r.name(), "output", r.output()));
            case AgentEvent.Done ignored -> frame(StreamFrameType.DONE, conversationId, null);
            case AgentEvent.Failed f -> frame(StreamFrameType.ERROR, conversationId,
                    f.message() == null ? "Unknown error" : f.message());
        }));
    }

    private static Map<String, Object> toolData(String id, String name, String payloadKey, String payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put(payloadKey, payload == null ? "" : payload);
        return data;
    }

    private static Map<String, Object> frame(StreamFrameType type, String conversationId, Object payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type.type());
        if (payload != null) frame.put("data", payload);
        frame.put("conversationId", conversationId);
        frame.put("time", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return frame;
    }

    private void sendFrame(Map<String, Object> frame) {
        WebSocketSession session = wsSession.get();
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (IOException e) {
            log.warn("WS push failed, dropping stream frame: {}", e.getMessage());
        }
    }

    private static String buildBackgroundMessageHtml(String text) {
        return Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(text, Instant.now()));
    }
}

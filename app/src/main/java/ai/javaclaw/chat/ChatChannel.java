package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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

    private final Agent agent;
    private final ChannelRegistry channelRegistry;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final AtomicReference<WebSocketSession> wsSession = new AtomicReference<>();

    public ChatChannel(Agent agent, ChannelRegistry channelRegistry, ChatMemoryRepository chatMemoryRepository, ObjectMapper objectMapper) {
        this.agent = agent;
        this.channelRegistry = channelRegistry;
        this.chatMemoryRepository = chatMemoryRepository;
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
        chatMemoryRepository.findConversationIds().stream()
                .filter(id -> !id.equals("web"))
                .forEach(result::add);
        return result;
    }

    /**
     * Loads conversation history for the given conversationId as HTML bubbles.
     * Returns a single welcome bubble if no history exists yet.
     */
    public List<String> loadHistoryAsHtml(String conversationId) {
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history.isEmpty()) {
            return List.of(ChatHtml.agentBubble("Hi! I'm your JavaClaw assistant. How can I help you today?"));
        }
        List<String> bubbles = new ArrayList<>();
        for (Message msg : history) {
            if (msg instanceof UserMessage) bubbles.add(ChatHtml.userBubble(msg.getText()));
            else if (msg instanceof AssistantMessage) bubbles.add(ChatHtml.agentBubble(msg.getText()));
        }
        return bubbles;
    }

    /**
     * Handles a chat message from the web UI for the given conversationId.
     * The response is streamed to the WebSocket session as JSON frames
     * ({@code chunk}/{@code done}/{@code error}); the full response text is returned.
     */
    public String chat(String conversationId, String message) {
        channelRegistry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent(getName(), message));

        return agent.respondTo(conversationId, message, ResponseListener.of(
                token -> sendChunkFrame(conversationId, token),
                () -> sendDoneFrame(conversationId),
                error -> sendErrorFrame(conversationId, error)));
    }

    private void sendChunkFrame(String conversationId, String token) {
        sendFrame(frame(StreamFrameType.CHUNK, conversationId, token));
    }

    private void sendDoneFrame(String conversationId) {
        sendFrame(frame(StreamFrameType.DONE, conversationId, null));
    }

    private void sendErrorFrame(String conversationId, String error) {
        sendFrame(frame(StreamFrameType.ERROR, conversationId, error == null ? "Unknown error" : error));
    }

    private static Map<String, Object> frame(StreamFrameType type, String conversationId, Object payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type.type());
        if (payload != null) frame.put("data", payload);
        frame.put("conversationId", conversationId);
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
        return Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(text));
    }
}

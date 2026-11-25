package ai.javaclaw.chat.ws;

import ai.javaclaw.chat.ChatChannel;
import ai.javaclaw.chat.ChatHtml;
import ai.javaclaw.chat.Htmx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The web chat's WebSocket: one connection per browser tab. Frames from a tab name the chat
 * session they are about; {@link ChatChannel} checks it is one of the web conversation's.
 *
 * <p>An answer runs on its own thread, so the tab can switch session, start a new chat or open a
 * channel's view while it streams.
 */
@Component
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatChannel chatChannel;
    private final ObjectMapper objectMapper;
    private final Executor answers;

    @Autowired
    public ChatWebSocketHandler(ChatChannel chatChannel, ObjectMapper objectMapper) {
        this(chatChannel, objectMapper, Executors.newVirtualThreadPerTaskExecutor());
    }

    ChatWebSocketHandler(ChatChannel chatChannel, ObjectMapper objectMapper, Executor answers) {
        this.chatChannel = chatChannel;
        this.objectMapper = objectMapper;
        this.answers = answers;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebChat WebSocket connected: {}", session.getId());
        chatChannel.open(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatChannel.close(session);
        log.info("WebChat WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) payload.get("type");

        try {
            switch (type == null ? "" : type) {
                case "userMessage" -> handleUserMessage(session, payload);
                case "newChat" -> chatChannel.newChat(session);
                case "switchSession" -> chatChannel.switchTo(session, text(payload, "sessionId"));
                case "viewSession" -> chatChannel.showChannel(session, text(payload, "sessionId"));
                case "renameSession" -> chatChannel.rename(webSession(payload), text(payload, "title"));
                default -> log.debug("Ignoring WebChat frame of type {}", type);
            }
        } catch (IllegalArgumentException e) {
            // A session of another conversation, an unknown session or a blank title: nothing changes
            log.warn("Refused WebChat {} request: {}", type, e.getMessage());
        }
    }

    private static String text(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("No " + name);
        }
        return text;
    }

    /** The frame's session, which must be one of the web conversation's. */
    private String webSession(Map<String, Object> payload) {
        String sessionId = text(payload, "sessionId");
        if (!chatChannel.isWebSession(sessionId)) {
            throw new IllegalArgumentException("Session " + sessionId + " is not one of the web chat's");
        }
        return sessionId;
    }

    private void handleUserMessage(WebSocketSession session, Map<String, Object> payload) throws Exception {
        String userMessage = (String) payload.get("message");

        if (userMessage == null || userMessage.isBlank()) return;
        String question = userMessage.trim();
        // A command (/new) is answered here; it reaches neither the model nor the history
        if (chatChannel.runCommand(session, question)) return;
        String sessionId = webSession(payload);

        // Echo the question and show the typing indicator in every tab showing the session
        chatChannel.sendHtmlTo(sessionId,
                Htmx.oobAppend("chat-messages", ChatHtml.userBubble(question, Instant.now())),
                Htmx.oobReplace("typing-indicator", ChatHtml.typingDots()));

        answers.execute(() -> {
            try {
                chatChannel.chat(sessionId, question);
            } catch (RuntimeException ex) {
                log.warn("Chat request failed in session {}", sessionId, ex);
                chatChannel.failed(sessionId, genericUserFacingError(ex));
            }
        });
    }

    private static String genericUserFacingError(RuntimeException ex) {
        return "An error occurred while contacting the AI provider.\nDetails: " + summarizeError(ex);
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }

        return message;
    }
}

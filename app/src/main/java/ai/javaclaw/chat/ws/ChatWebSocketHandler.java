package ai.javaclaw.chat.ws;

import ai.javaclaw.chat.ChatChannel;
import ai.javaclaw.chat.ChatHtml;
import ai.javaclaw.chat.Htmx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatChannel chatChannel;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(ChatChannel chatChannel, ObjectMapper objectMapper) {
        this.chatChannel = chatChannel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        chatChannel.setWsSession(session);
        log.info("WebChat WebSocket connected: {}", session.getId());

        chatChannel.showWebChat();
        chatChannel.flushPendingMessages();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatChannel.clearWsSession(session);
        log.info("WebChat WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) payload.get("type");

        try {
            switch (type == null ? "" : type) {
                case "userMessage" -> handleUserMessage(payload);
                case "newChat" -> chatChannel.newChat();
                case "switchSession" -> chatChannel.switchTo(text(payload, "sessionId"));
                case "viewSession" -> chatChannel.showChannel(text(payload, "sessionId"));
                case "renameSession" -> chatChannel.rename(text(payload, "sessionId"), text(payload, "title"));
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

    private void handleUserMessage(Map<String, Object> payload) throws Exception {
        String userMessage = (String) payload.get("message");

        if (userMessage == null || userMessage.isBlank()) return;
        userMessage = userMessage.trim();
        // A command (/new) is answered here; it reaches neither the model nor the history
        if (chatChannel.runCommand(userMessage)) return;

        // Echo user message + show typing indicator
        chatChannel.sendHtml(
                Htmx.oobAppend("chat-messages", ChatHtml.userBubble(userMessage, Instant.now())),
                Htmx.oobReplace("typing-indicator", ChatHtml.typingDots()));

        try {
            // Call agent (blocking — the response is streamed to the client as JSON frames
            // by ChatChannel while this call runs; background tasks may push messages too)
            chatChannel.chat(userMessage);
            chatChannel.sendHtml(Htmx.oobReplace("typing-indicator", ""));
        } catch (RuntimeException ex) {
            log.warn("Chat request failed", ex);
            chatChannel.sendHtml(
                    Htmx.oobAppend("chat-messages", ChatHtml.agentError(genericUserFacingError(ex), Instant.now())),
                    Htmx.oobReplace("typing-indicator", ""));
        }
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
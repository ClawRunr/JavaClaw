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

import java.util.List;
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

        List<String> ids = chatChannel.conversationIds();
        String selectedId = ids.getFirst();

        String conversationSelector = ChatHtml.conversationSelector(ids, selectedId);
        String bubbles = String.join(System.lineSeparator(), chatChannel.loadHistoryAsHtml(selectedId));
        String inputArea = ChatHtml.chatInputArea(selectedId);
        chatChannel.sendHtml(
                Htmx.oobInnerHtml("channel-selector", conversationSelector),
                Htmx.oobInnerHtml("chat-messages", bubbles),
                Htmx.oobInnerHtml("chat-input-area", inputArea));
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

        if ("channelChanged".equals(type)) {
            handleChannelChanged(payload);
        } else if ("userMessage".equals(type)) {
            handleUserMessage(payload);
        }
    }

    private void handleChannelChanged(Map<String, Object> payload) throws Exception {
        String conversationId = (String) payload.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) return;

        String bubbles = String.join(System.lineSeparator(), chatChannel.loadHistoryAsHtml(conversationId));
        String inputArea = ChatHtml.chatInputArea(conversationId);
        chatChannel.sendHtml(
                Htmx.oobInnerHtml("chat-messages", bubbles),
                Htmx.oobInnerHtml("chat-input-area", inputArea));
    }

    private void handleUserMessage(Map<String, Object> payload) throws Exception {
        String conversationId = (String) payload.get("conversationId");
        String userMessage = (String) payload.get("message");

        if (userMessage == null || userMessage.isBlank()) return;
        userMessage = userMessage.trim();
        if (conversationId == null || conversationId.isBlank()) conversationId = "web";

        // Echo user message + show typing indicator
        chatChannel.sendHtml(
                Htmx.oobAppend("chat-messages", ChatHtml.userBubble(userMessage)),
                Htmx.oobReplace("typing-indicator", ChatHtml.typingDots()));

        try {
            // Call agent (blocking — background tasks may push messages via ChatChannel during this)
            ChatChannel.ChatResult result = chatChannel.chat(conversationId, userMessage);

            chatChannel.sendHtml(
                    Htmx.oobAppend("chat-messages", ChatHtml.agentTurn(result.text(), result.toolSteps())),
                    Htmx.oobReplace("typing-indicator", ""));
        } catch (RuntimeException ex) {
            log.warn("Chat request failed for conversation {}", conversationId, ex);
            chatChannel.sendHtml(
                    Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(genericUserFacingError(ex))),
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
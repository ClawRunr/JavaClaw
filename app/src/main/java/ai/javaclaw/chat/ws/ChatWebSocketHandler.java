package ai.javaclaw.chat.ws;

import ai.javaclaw.utils.Errors;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatChannel chatChannel;
    private final ObjectMapper objectMapper;
    private final Supplier<Executor> turnExecutors;
    /**
     * Each session answers its messages one at a time, in order, off the socket's own thread. The
     * container hands a session its next frame only after the previous one is handled, so running
     * the agent there would leave it unable to receive an approval decision — or to notice that
     * the client went away — while a turn waits for one.
     */
    private final Map<WebSocketSession, Executor> turns = new ConcurrentHashMap<>();

    @Autowired
    public ChatWebSocketHandler(ChatChannel chatChannel, ObjectMapper objectMapper) {
        this(chatChannel, objectMapper, ChatWebSocketHandler::newTurnExecutor);
    }

    /** One virtual thread per session, so its turns run one at a time and in order. */
    static ExecutorService newTurnExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofVirtual().name("web-chat-turn-", 0).factory());
    }

    /** @param turnExecutors makes the per-session executor turns run on; tests pass a direct one */
    ChatWebSocketHandler(ChatChannel chatChannel, ObjectMapper objectMapper, Supplier<Executor> turnExecutors) {
        this.chatChannel = chatChannel;
        this.objectMapper = objectMapper;
        this.turnExecutors = turnExecutors;
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
        chatChannel.replayPendingApprovals(session);
        chatChannel.flushPendingMessages();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatChannel.clearWsSession(session);
        if (turns.remove(session) instanceof ExecutorService executor) {
            // A running turn finishes on its own; its pending approvals were just denied
            executor.shutdown();
        }
        log.info("WebChat WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) payload.get("type");

        if ("channelChanged".equals(type)) {
            handleChannelChanged(session, payload);
        } else if ("userMessage".equals(type)) {
            handleUserMessage(session, payload);
        } else if ("approvalDecision".equals(type)) {
            handleApprovalDecision(session, payload);
        }
    }

    private void handleChannelChanged(WebSocketSession session, Map<String, Object> payload) throws Exception {
        String conversationId = (String) payload.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) return;

        chatChannel.viewConversation(session, conversationId);
        String bubbles = String.join(System.lineSeparator(), chatChannel.loadHistoryAsHtml(conversationId));
        String inputArea = ChatHtml.chatInputArea(conversationId);
        chatChannel.sendHtml(
                Htmx.oobInnerHtml("chat-messages", bubbles),
                Htmx.oobInnerHtml("chat-input-area", inputArea));
        chatChannel.replayPendingApprovals(session);
    }

    private void handleUserMessage(WebSocketSession session, Map<String, Object> payload) throws Exception {
        String conversationId = (String) payload.get("conversationId");
        String userMessage = (String) payload.get("message");

        if (userMessage == null || userMessage.isBlank()) return;
        String text = userMessage.trim();
        String conversation = conversationId == null || conversationId.isBlank() ? "web" : conversationId;

        // Echo user message + show typing indicator
        chatChannel.sendHtml(
                Htmx.oobAppend("chat-messages", ChatHtml.userBubble(text)),
                Htmx.oobReplace("typing-indicator", ChatHtml.typingDots()));

        turns.computeIfAbsent(session, _ -> turnExecutors.get()).execute(() -> runTurn(conversation, text));
    }

    /** The response is streamed to the client as JSON frames by ChatChannel while this runs. */
    private void runTurn(String conversationId, String userMessage) {
        try {
            chatChannel.chat(conversationId, userMessage);
            chatChannel.sendHtml(Htmx.oobReplace("typing-indicator", ""));
        } catch (Exception ex) {
            log.warn("Chat request failed for conversation {}", conversationId, ex);
            try {
                chatChannel.sendHtml(
                        Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(genericUserFacingError(ex))),
                        Htmx.oobReplace("typing-indicator", ""));
            } catch (Exception sendFailure) {
                log.warn("Could not report the failure to the web chat client: {}", sendFailure.getMessage());
            }
        }
    }

    private void handleApprovalDecision(WebSocketSession session, Map<String, Object> payload) {
        String approvalId = payload.get("approvalId") instanceof String s ? s : null;
        String hash = payload.get("argumentsHash") instanceof String s ? s : null;
        String decision = payload.get("decision") instanceof String s ? s : null;
        if (!chatChannel.decide(session, approvalId, hash, decision)) {
            log.info("Approval decision for {} was not accepted (unknown, already answered or does not match)", approvalId);
        }
    }

    /** How many sessions still hold a turn executor; each is dropped and shut down when its session closes. */
    int sessionsWithTurnExecutors() {
        return turns.size();
    }

    private static String genericUserFacingError(Exception ex) {
        return "An error occurred while contacting the AI provider.\nDetails: " + Errors.summarize(ex);
    }
}

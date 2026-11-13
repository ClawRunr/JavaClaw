package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.HumanQuestionHandler;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * GUI channel for the web chat interface.
 * Pushes messages directly to the active WebSocket session when connected,
 * falling back to an in-memory queue for REST polling.
 * <p>
 * Also the web chat's {@link HumanQuestionHandler}: it knows which conversation each connected
 * client is looking at, so a turn only pauses for approval when a client that will actually see
 * the request is there. Otherwise the call is denied and the turn carries on.
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatChannel implements Channel, HumanQuestionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);

    /** The conversation a freshly connected client shows; see {@link #conversationIds()}. */
    static final String DEFAULT_CONVERSATION = "web";

    private final Agent agent;
    private final ChannelRegistry channelRegistry;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    /** The session frames and pushes go to: the most recently connected one. */
    private final AtomicReference<WebSocketSession> wsSession = new AtomicReference<>();
    /** Every connected session and the conversation it is showing. */
    private final Map<WebSocketSession, String> viewing = new ConcurrentHashMap<>();

    public ChatChannel(Agent agent, ChannelRegistry channelRegistry, ChatMemoryRepository chatMemoryRepository,
                       ObjectMapper objectMapper) {
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
     * Called by the WebSocket handler when a client connects. It starts out showing the
     * {@value #DEFAULT_CONVERSATION} conversation.
     */
    public void setWsSession(WebSocketSession session) {
        viewing.put(session, DEFAULT_CONVERSATION);
        wsSession.set(session);
    }

    /**
     * Called by the WebSocket handler when the client disconnects. A paused conversation stays
     * paused; its requests are shown again when a client comes back to it.
     */
    public void clearWsSession(WebSocketSession session) {
        viewing.remove(session);
        wsSession.compareAndSet(session, null);
    }

    /** Called when a client switches to another conversation in the selector. */
    public void viewConversation(WebSocketSession session, String conversationId) {
        viewing.replace(session, conversationId);
    }

    /** True when the session frames go to is open and showing this conversation. */
    @Override
    public boolean canAsk(String conversationId) {
        WebSocketSession session = wsSession.get();
        return session != null && session.isOpen() && conversationId.equals(viewing.get(session));
    }

    /**
     * Shows a (re)connected client the approvals still waiting in the conversation it is showing —
     * the card that was on screen is gone after a reload.
     */
    public void replayPendingApprovals(WebSocketSession session) {
        String conversationId = viewing.get(session);
        if (conversationId == null || session != wsSession.get()) {
            return;
        }
        agent.waitingForApproval(conversationId).forEach(request ->
                sendFrame(frame(StreamFrameType.APPROVAL_REQUESTED, conversationId, approvalRequestData(request))));
    }

    /**
     * Applies a decision a client sent. Only the conversation that client is showing can be
     * answered; a decision that does not match a waiting request exactly is ignored. When it was the
     * last open request, the resumed turn streams to the client like any other answer.
     *
     * @return whether the decision was accepted
     */
    public boolean decide(WebSocketSession session, String approvalId, String argumentsHash, String decision) {
        String conversationId = viewing.get(session);
        Optional<ApprovalDecision> parsed = ApprovalDecision.fromWireValue(decision);
        if (conversationId == null || parsed.isEmpty()) {
            return false;
        }
        return agent.decide(conversationId, approvalId, argumentsHash, parsed.get(), this, frames(conversationId))
                != Agent.DecisionResult.IGNORED;
    }

    /**
     * Sends a raw HTML fragment to the active WebSocket session.
     * Used by the WebSocket handler to push user/agent bubbles and typing indicators.
     */
    public void sendHtml(String... html) throws IOException {
        WebSocketSession session = wsSession.get();
        if (session != null && session.isOpen()) {
            send(session, new TextMessage(String.join(System.lineSeparator(), html)));
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
        result.add(DEFAULT_CONVERSATION);
        chatMemoryRepository.findConversationIds().stream()
                .filter(id -> !id.equals(DEFAULT_CONVERSATION))
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
     * ({@code chunk}/{@code toolCall}/{@code toolResult}/{@code approvalRequested}/
     * {@code approvalResolved}/{@code done}/{@code error}).
     */
    public void chat(String conversationId, String message) {
        channelRegistry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent(getName(), message));

        agent.respondTo(conversationId, message, frames(conversationId), this);
    }

    /** Turns agent events into the JSON frames the page renders. */
    private Consumer<AgentEvent> frames(String conversationId) {
        return event -> sendFrame(switch (event) {
            case AgentEvent.Token t -> frame(StreamFrameType.CHUNK, conversationId, t.text());
            case AgentEvent.ToolCall c -> frame(StreamFrameType.TOOL_CALL, conversationId,
                    toolData(c.id(), c.name(), "input", c.input()));
            case AgentEvent.ToolResult r -> frame(StreamFrameType.TOOL_RESULT, conversationId,
                    toolData(r.id(), r.name(), "output", r.output()));
            case AgentEvent.ApprovalRequested a -> frame(StreamFrameType.APPROVAL_REQUESTED, conversationId,
                    approvalRequestData(a.request()));
            case AgentEvent.ApprovalResolved a -> frame(StreamFrameType.APPROVAL_RESOLVED, conversationId,
                    approvalResolvedData(a));
            case AgentEvent.Done ignored -> frame(StreamFrameType.DONE, conversationId, null);
            case AgentEvent.Failed f -> frame(StreamFrameType.ERROR, conversationId,
                    f.message() == null ? "Unknown error" : f.message());
        });
    }

    private static Map<String, Object> toolData(String id, String name, String payloadKey, String payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put(payloadKey, payload == null ? "" : payload);
        return data;
    }

    /** Everything the card needs; {@code decisions} are the buttons it may show. */
    static Map<String, Object> approvalRequestData(ApprovalRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approvalId", request.approvalId());
        data.put("toolCallId", request.toolCallId() == null ? "" : request.toolCallId());
        data.put("toolName", request.toolName());
        data.put("arguments", request.arguments());
        data.put("argumentsHash", request.argumentsHash());
        data.put("decisions", request.decisions().stream().map(ApprovalDecision::wireValue).toList());
        return data;
    }

    private static Map<String, Object> approvalResolvedData(AgentEvent.ApprovalResolved resolved) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (resolved.approvalId() != null) {
            data.put("approvalId", resolved.approvalId());
        }
        data.put("toolCallId", resolved.toolCallId() == null ? "" : resolved.toolCallId());
        data.put("toolName", resolved.toolName());
        data.put("outcome", resolved.approved() ? "allowed" : "denied");
        if (!resolved.approved()) {
            data.put("detail", resolved.denial().message());
        }
        return data;
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
            send(session, new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (IOException e) {
            log.warn("WS push failed, dropping stream frame: {}", e.getMessage());
        }
    }

    /**
     * Frames come from the agent's thread, HTML from the socket's own thread and background pushes
     * from task threads; a WebSocket session must not be written to by two of them at once.
     */
    private static void send(WebSocketSession session, TextMessage message) throws IOException {
        synchronized (session) {
            session.sendMessage(message);
        }
    }

    private static String buildBackgroundMessageHtml(String text) {
        return Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(text));
    }
}

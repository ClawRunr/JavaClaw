package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.ArgumentsHash;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.approval.HumanQuestionHandler;
import ai.javaclaw.approval.ToolRisk;
import ai.javaclaw.approval.PendingApprovals;
import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatChannelTest {

    @Mock Agent agent;
    @Mock ChatMemoryRepository chatMemoryRepository;

    PendingApprovals pendingApprovals = new PendingApprovals();
    ChatChannel chatChannel;

    @BeforeEach
    void setUp() {
        chatChannel = new ChatChannel(agent, new ChannelRegistry(), chatMemoryRepository, new ObjectMapper(), pendingApprovals);
    }

    // -----------------------------------------------------------------------
    // conversationIds
    // -----------------------------------------------------------------------

    @Test
    void conversationIdsAlwaysContainsWebFirst() {
        when(chatMemoryRepository.findConversationIds()).thenReturn(List.of("telegram-42", "web"));

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids).first().isEqualTo("web");
    }

    @Test
    void conversationIdsIncludesWebEvenWhenRepositoryReturnsEmpty() {
        when(chatMemoryRepository.findConversationIds()).thenReturn(List.of());

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids).containsExactly("web");
    }

    @Test
    void conversationIdsIncludesOtherChannelsAfterWeb() {
        when(chatMemoryRepository.findConversationIds()).thenReturn(List.of("telegram-42", "telegram-99"));

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids).containsExactly("web", "telegram-42", "telegram-99");
    }

    @Test
    void conversationIdsDeduplicatesWeb() {
        when(chatMemoryRepository.findConversationIds()).thenReturn(List.of("web", "telegram-42"));

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids.stream().filter("web"::equals)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // loadHistoryAsHtml
    // -----------------------------------------------------------------------

    @Test
    void loadHistoryReturnsWelcomeBubbleWhenNoHistory() {
        when(chatMemoryRepository.findByConversationId("web")).thenReturn(List.of());

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles).hasSize(1);
        assertThat(bubbles.get(0)).contains("ar-msg--agent");
    }

    @Test
    void loadHistoryRendersUserAndAgentBubbles() {
        when(chatMemoryRepository.findByConversationId("web")).thenReturn(List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi there")
        ));

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles).hasSize(2);
        assertThat(bubbles.get(0)).contains("ar-msg--user").contains("Hello");
        assertThat(bubbles.get(1)).contains("ar-msg--agent").contains("Hi there");
    }

    @Test
    void loadHistoryEscapesHtmlInMessages() {
        when(chatMemoryRepository.findByConversationId("web")).thenReturn(List.of(
                new UserMessage("<script>alert('xss')</script>")
        ));

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles.get(0)).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void loadHistoryUsesSuppliedConversationId() {
        when(chatMemoryRepository.findByConversationId("telegram-42")).thenReturn(List.of());

        chatChannel.loadHistoryAsHtml("telegram-42");

        verify(chatMemoryRepository).findByConversationId("telegram-42");
    }

    // -----------------------------------------------------------------------
    // chat
    // -----------------------------------------------------------------------

    @Test
    void chatDelegatesToAgentWithConversationId() {
        chatChannel.chat("web", "hello");

        verify(agent).respondTo(eq("web"), eq("hello"), anyConsumer(), any(HumanQuestionHandler.class));
    }

    @Test
    void chatUsesSuppliedConversationId() {
        chatChannel.chat("telegram-42", "hello");

        verify(agent).respondTo(eq("telegram-42"), eq("hello"), anyConsumer(), any(HumanQuestionHandler.class));
    }

    // -----------------------------------------------------------------------
    // sendMessage / WebSocket session
    // -----------------------------------------------------------------------

    @Test
    void sendHtmlDoesNothingWhenNoSessionSet() throws IOException {
        // should not throw
        chatChannel.sendHtml("<div>test</div>");
    }

    @Test
    void sendHtmlWritesToActiveSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);

        chatChannel.sendHtml("<div>test</div>");

        verify(session).sendMessage(new TextMessage("<div>test</div>"));
    }

    @Test
    void sendHtmlDoesNothingWhenSessionIsClosed() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        chatChannel.setWsSession(session);

        chatChannel.sendHtml("<div>test</div>");

        verify(session, never()).sendMessage(any());
    }

    @Test
    void clearWsSessionRemovesSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        chatChannel.setWsSession(session);
        chatChannel.clearWsSession(session);

        chatChannel.sendHtml("<div>test</div>");

        verify(session, never()).sendMessage(any());
    }

    @Test
    void clearWsSessionIgnoresDifferentSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketSession otherSession = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);
        chatChannel.clearWsSession(otherSession);

        chatChannel.sendHtml("<div>test</div>");

        verify(session).sendMessage(any());
    }

    @Test
    void sendMessagePushesOobHtmlToActiveSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);

        chatChannel.sendMessage("Background result");

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void flushPendingMessagesDeliversMessagesBufferedWhileSendFailed() throws IOException {
        WebSocketSession failingSession = mock(WebSocketSession.class);
        when(failingSession.isOpen()).thenReturn(true);
        doThrow(new IOException("connection gone")).when(failingSession).sendMessage(any());
        chatChannel.setWsSession(failingSession);
        chatChannel.sendMessage("Background result");

        WebSocketSession session = openSession();
        chatChannel.flushPendingMessages();

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void flushPendingMessagesDoesNothingWhenBufferIsEmpty() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        chatChannel.setWsSession(session);

        chatChannel.flushPendingMessages();

        verify(session, never()).sendMessage(any());
    }

    // -----------------------------------------------------------------------
    // streaming frames
    // -----------------------------------------------------------------------

    @Test
    void chatStreamsTokensAsChunkFramesFollowedByDoneFrame() throws IOException {
        WebSocketSession session = openSession();
        agentStreams(events -> {
            events.accept(new AgentEvent.Token("Hello "));
            events.accept(new AgentEvent.Token("world"));
            events.accept(new AgentEvent.Done());
        });

        chatChannel.chat("web", "hello");

        List<Map<String, Object>> frames = capturedFrames(session, 3);
        assertThat(frames.get(0))
                .containsEntry("type", "chunk")
                .containsEntry("data", "Hello ")
                .containsEntry("conversationId", "web");
        assertThat(frames.get(1))
                .containsEntry("type", "chunk")
                .containsEntry("data", "world");
        assertThat(frames.get(2))
                .containsEntry("type", "done")
                .containsEntry("conversationId", "web")
                .doesNotContainKey("data");
    }

    @Test
    void chatStreamsToolCallAndResultFrames() throws IOException {
        WebSocketSession session = openSession();
        agentStreams(events -> {
            events.accept(new AgentEvent.ToolCall("call-1", "readFile", "{\"path\":\"pom.xml\"}"));
            events.accept(new AgentEvent.ToolResult("call-1", "readFile", "file contents"));
            events.accept(new AgentEvent.Done());
        });

        chatChannel.chat("web", "hello");

        List<Map<String, Object>> frames = capturedFrames(session, 3);
        assertThat(frames.get(0))
                .containsEntry("type", "toolCall")
                .containsEntry("conversationId", "web")
                .containsEntry("data", Map.of("id", "call-1", "name", "readFile",
                        "input", "{\"path\":\"pom.xml\"}"));
        assertThat(frames.get(1))
                .containsEntry("type", "toolResult")
                .containsEntry("data", Map.of("id", "call-1", "name", "readFile",
                        "output", "file contents"));
        assertThat(frames.get(2)).containsEntry("type", "done");
    }

    @Test
    void chatStreamsErrorFrameWhenResponseFails() throws IOException {
        WebSocketSession session = openSession();
        agentStreams(events -> events.accept(new AgentEvent.Failed("boom")));

        chatChannel.chat("web", "hello");

        List<Map<String, Object>> frames = capturedFrames(session, 1);
        assertThat(frames.get(0))
                .containsEntry("type", "error")
                .containsEntry("data", "boom")
                .containsEntry("conversationId", "web");
    }

    @Test
    void chatDropsStreamFramesWhenNoSessionIsActive() {
        agentStreams(events -> {
            events.accept(new AgentEvent.Token("Hello"));
            events.accept(new AgentEvent.Done());
        });

        // should not throw
        chatChannel.chat("web", "hello");
    }

    // -----------------------------------------------------------------------
    // tool approvals
    // -----------------------------------------------------------------------

    @Test
    void canAskOnlyWhileAConnectedClientShowsTheConversation() {
        assertThat(chatChannel.canAsk("web")).as("nobody connected").isFalse();

        WebSocketSession session = connectedSession();
        assertThat(chatChannel.canAsk("web")).isTrue();
        assertThat(chatChannel.canAsk("telegram-42")).isFalse();

        chatChannel.viewConversation(session, "telegram-42");
        assertThat(chatChannel.canAsk("web")).isFalse();
        assertThat(chatChannel.canAsk("telegram-42")).isTrue();

        chatChannel.clearWsSession(session);
        assertThat(chatChannel.canAsk("telegram-42")).isFalse();
    }

    @Test
    void chatLetsTheAgentAskThroughThisChannel() {
        chatChannel.chat("web", "hello");

        verify(agent).respondTo(eq("web"), eq("hello"), anyConsumer(), eq(chatChannel));
    }

    @Test
    void disconnectingTheLastClientDeniesItsPendingApprovals() {
        WebSocketSession session = connectedSession();
        PendingApprovals.Pending pending = pendingApprovals.open(request("call-1", "web"));
        PendingApprovals.Pending elsewhere = pendingApprovals.open(request("call-2", "telegram-42"));

        chatChannel.clearWsSession(session);

        assertThat(pending.answer()).isCompletedWithValueMatching(resolution ->
                resolution instanceof PendingApprovals.Resolution.Cancelled(Denial denial)
                        && denial.reason() == Denial.Reason.NOBODY_TO_ASK
                        && denial.detail().contains("disconnected"));
        assertThat(elsewhere.answer()).isNotDone();
    }

    @Test
    void anOlderClientClosingKeepsApprovalsTheActiveClientCanStillAnswer() {
        WebSocketSession older = connectedSession();
        connectedSession();
        PendingApprovals.Pending pending = pendingApprovals.open(request("call-1", "web"));

        chatChannel.clearWsSession(older);

        assertThat(pending.answer()).isNotDone();
    }

    @Test
    void decisionsAnswerOnlyTheConversationTheClientShows() {
        WebSocketSession session = connectedSession();
        ApprovalRequest request = request("call-1", "web");
        PendingApprovals.Pending pending = pendingApprovals.open(request);

        chatChannel.viewConversation(session, "telegram-42");
        assertThat(chatChannel.decide(session, "call-1", request.argumentsHash(), "allow-once")).isFalse();

        chatChannel.viewConversation(session, "web");
        assertThat(chatChannel.decide(session, "call-1", request.argumentsHash(), "allow-always")).as("not offered").isFalse();
        assertThat(chatChannel.decide(session, "call-1", "wrong-hash", "allow-once")).isFalse();
        assertThat(pending.answer()).isNotDone();

        assertThat(chatChannel.decide(session, "call-1", request.argumentsHash(), "allow-once")).isTrue();
        assertThat(pending.answer()).isCompletedWithValue(
                new PendingApprovals.Resolution.Decided(ApprovalDecision.ALLOW_ONCE));
        assertThat(chatChannel.decide(session, "call-1", request.argumentsHash(), "allow-once")).as("reused").isFalse();
    }

    @Test
    void chatStreamsApprovalFramesWithTheExactArgumentsAndAllowedDecisions() throws IOException {
        WebSocketSession session = connectedSession();
        ApprovalRequest request = request("call-1", "web");
        agentStreams(events -> {
            events.accept(new AgentEvent.ApprovalRequested(request));
            events.accept(new AgentEvent.ApprovalResolved("call-1", "call-1", "Write",
                    new Denial(Denial.Reason.TIMEOUT, "nobody approved it within 2 minutes")));
            events.accept(new AgentEvent.Done());
        });

        chatChannel.chat("web", "hello");

        List<Map<String, Object>> frames = capturedFrames(session, 3);
        assertThat(frames.get(0)).containsEntry("type", "approvalRequested").containsEntry("conversationId", "web");
        @SuppressWarnings("unchecked")
        Map<String, Object> asked = (Map<String, Object>) frames.get(0).get("data");
        assertThat(asked)
                .containsEntry("approvalId", "call-1")
                .containsEntry("toolName", "Write")
                .containsEntry("arguments", request.arguments())
                .containsEntry("risk", "WRITE")
                .containsEntry("argumentsHash", request.argumentsHash())
                .containsEntry("decisions", List.of("allow-once", "deny"))
                .containsEntry("details", Map.of("workingDirectory", "/srv/javaclaw"));
        assertThat(frames.get(1)).containsEntry("type", "approvalResolved")
                .containsEntry("data", Map.of("approvalId", "call-1", "toolCallId", "call-1", "toolName", "Write",
                        "outcome", "denied", "reason", "timeout", "detail", "nobody approved it within 2 minutes"));
    }

    @Test
    void aReconnectingClientIsShownTheApprovalsStillWaiting() throws IOException {
        pendingApprovals.open(request("call-1", "web"));
        WebSocketSession session = connectedSession();

        chatChannel.replayPendingApprovals(session);

        assertThat(capturedFrames(session, 1).getFirst()).containsEntry("type", "approvalRequested");
    }

    private static ApprovalRequest request(String id, String conversationId) {
        String arguments = "{\"filePath\":\"<img src=x onerror=alert(1)>\",\"content\":\"</pre><script>x()</script>\"}";
        return new ApprovalRequest(id, id, conversationId, "Write", arguments, ToolRisk.WRITE,
                ArgumentsHash.of("Write", arguments), List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY),
                Map.of("workingDirectory", "/srv/javaclaw"), null, Instant.now().plusSeconds(120));
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<AgentEvent> anyConsumer() {
        return any(java.util.function.Consumer.class);
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);
        return session;
    }

    /** Like {@link #openSession()}, for tests where whether it is asked if it is open varies. */
    private WebSocketSession connectedSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);
        return session;
    }

    private void agentStreams(java.util.function.Consumer<java.util.function.Consumer<AgentEvent>> progress) {
        doAnswer(invocation -> {
            progress.accept(invocation.getArgument(2));
            return null;
        }).when(agent).respondTo(eq("web"), eq("hello"), anyConsumer(), any(HumanQuestionHandler.class));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> capturedFrames(WebSocketSession session, int expectedCount) throws IOException {
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.times(expectedCount)).sendMessage(messageCaptor.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        return messageCaptor.getAllValues().stream()
                .map(message -> (Map<String, Object>) objectMapper.readValue(message.getPayload(), Map.class))
                .toList();
    }
}

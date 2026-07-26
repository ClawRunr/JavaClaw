package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatChannelTest {

    @Mock Agent agent;
    @Mock ChatMemoryRepository chatMemoryRepository;

    ChatChannel chatChannel;

    @BeforeEach
    void setUp() {
        chatChannel = new ChatChannel(agent, new ChannelRegistry(), chatMemoryRepository, new ObjectMapper());
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
        when(agent.respondTo(eq("web"), eq("hello"), any(ResponseListener.class))).thenReturn("hi");

        String response = chatChannel.chat("web", "hello");

        assertThat(response).isEqualTo("hi");
        verify(agent).respondTo(eq("web"), eq("hello"), any(ResponseListener.class));
    }

    @Test
    void chatUsesSuppliedConversationId() {
        when(agent.respondTo(eq("telegram-42"), any(), any(ResponseListener.class))).thenReturn("reply");

        chatChannel.chat("telegram-42", "hello");

        verify(agent).respondTo(eq("telegram-42"), eq("hello"), any(ResponseListener.class));
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
        org.mockito.Mockito.doThrow(new IOException("connection gone")).when(failingSession).sendMessage(any());
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
        agentStreams(listener -> {
            listener.onToken("Hello ");
            listener.onToken("world");
            listener.onComplete();
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
    void chatStreamsErrorFrameWhenResponseFails() throws IOException {
        WebSocketSession session = openSession();
        agentStreams(listener -> listener.onError("boom"));

        chatChannel.chat("web", "hello");

        List<Map<String, Object>> frames = capturedFrames(session, 1);
        assertThat(frames.get(0))
                .containsEntry("type", "error")
                .containsEntry("data", "boom")
                .containsEntry("conversationId", "web");
    }

    @Test
    void chatDropsStreamFramesWhenNoSessionIsActive() {
        agentStreams(listener -> {
            listener.onToken("Hello");
            listener.onComplete();
        });

        // should not throw
        chatChannel.chat("web", "hello");
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);
        return session;
    }

    private void agentStreams(java.util.function.Consumer<ResponseListener> progress) {
        when(agent.respondTo(eq("web"), eq("hello"), any(ResponseListener.class))).thenAnswer(invocation -> {
            progress.accept(invocation.getArgument(2));
            return "";
        });
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

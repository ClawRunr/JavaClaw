package ai.javaclaw.chat.ws;

import ai.javaclaw.chat.ChatChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {

    @Test
    void handleUserMessageShowsNonTransientErrorAndClearsTypingIndicatorWhenAgentFails() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);

        doThrow(new RuntimeException("""
                HTTP 401 - {
                    "error": {
                        "message": "Incorrect API key provided: Test.",
                        "code": "invalid_api_key"
                    }
                }
                """)).when(chatChannel).chat("web", "hello");

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "conversationId", "web",
                "message", "hello"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        inOrder.verify(chatChannel).chat("web", "hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        verifyNoMoreInteractions(chatChannel);

        assertThat(String.join("", htmlCaptor.getAllValues().get(0)))
                .contains("hello")
                .contains("typing-indicator")
                .contains("ar-typing");

        assertThat(String.join("", htmlCaptor.getAllValues().get(1)))
                .contains("An error occurred while contacting the AI provider")
                .contains("Details: HTTP 401 - {")
                .contains("typing-indicator")
                .doesNotContain("ar-typing");
    }

    @Test
    void handleUserMessageShowsGenericProviderErrorForUnexpectedFailures() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);

        doThrow(new RuntimeException("boom")).when(chatChannel).chat(anyString(), anyString());

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "conversationId", "web",
                "message", "hello"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        inOrder.verify(chatChannel).chat("web", "hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());

        assertThat(String.join("", htmlCaptor.getAllValues().get(1)))
                .contains("An error occurred while contacting the AI provider")
                .contains("Details: boom");
    }

    @Test
    void handleUserMessageClearsTypingIndicatorWithoutAppendingBubbleWhenResponseWasStreamed() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);

        // the response is streamed to the client by ChatChannel while chat() runs

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "conversationId", "web",
                "message", "hello"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        inOrder.verify(chatChannel).chat("web", "hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        verifyNoMoreInteractions(chatChannel);

        assertThat(String.join("", htmlCaptor.getAllValues().get(1)))
                .contains("typing-indicator")
                .doesNotContain("streamed response")
                .doesNotContain("ar-msg--agent");
    }

    @Test
    void handleChannelChangedSendsHistoryAndInputArea() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);

        when(chatChannel.loadHistoryAsHtml("web")).thenReturn(List.of("<div>history</div>"));

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "channelChanged",
                "conversationId", "web"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        verify(chatChannel).sendHtml(htmlCaptor.capture());

        assertThat(String.join("", htmlCaptor.getValue()))
                .contains("chat-messages")
                .contains("history")
                .contains("chat-input-area");
    }

    @Test
    void afterConnectionEstablishedSendsSelectorHistoryAndInputArea() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);

        when(chatChannel.conversationIds()).thenReturn(List.of("web"));
        when(chatChannel.loadHistoryAsHtml("web")).thenReturn(List.of("<div>history</div>"));

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        verify(chatChannel).sendHtml(htmlCaptor.capture());

        assertThat(String.join("", htmlCaptor.getValue()))
                .contains("channel-selector")
                .contains("chat-messages")
                .contains("history")
                .contains("chat-input-area");
    }

    @Test
    void approvalDecisionsAreHandedToTheChannel() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "approvalDecision",
                "approvalId", "call-1",
                "argumentsHash", "abc",
                "decision", "allow-once"
        ))));

        verify(chatChannel).decide(session, "call-1", "abc", "allow-once");
    }

    @Test
    void theTurnRunsOffTheSocketThreadSoTheSessionCanStillReceiveADecision() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        List<Runnable> queued = new java.util.ArrayList<>();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> queued::add);

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "conversationId", "web",
                "message", "hello"
        ))));

        // handleTextMessage returned without running the agent
        verify(chatChannel, never()).chat(anyString(), anyString());
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        verify(chatChannel).chat("web", "hello");
    }

    @Test
    void switchingConversationTellsTheChannelAndReplaysItsPendingApprovals() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> Runnable::run);
        when(chatChannel.loadHistoryAsHtml("web")).thenReturn(List.of());

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "channelChanged",
                "conversationId", "web"
        ))));

        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).viewConversation(session, "web");
        inOrder.verify(chatChannel).replayPendingApprovals(session);
    }

    @Test
    void closingASessionShutsDownItsTurnExecutorOnceTheRunningTurnEnds() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        List<ExecutorService> executors = new CopyOnWriteArrayList<>();
        Set<Thread> turnThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch turnStarted = new CountDownLatch(1);
        CountDownLatch finishTurn = new CountDownLatch(1);
        doAnswer(_ -> {
            turnThreads.add(Thread.currentThread());
            turnStarted.countDown();
            finishTurn.await(5, TimeUnit.SECONDS);
            return null;
        }).when(chatChannel).chat("web", "hello");
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> {
            ExecutorService executor = ChatWebSocketHandler.newTurnExecutor();
            executors.add(executor);
            return executor;
        });

        handler.handleTextMessage(session, userMessage("hello"));
        assertThat(turnStarted.await(5, TimeUnit.SECONDS)).isTrue();
        handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);

        assertThat(executors).singleElement().satisfies(e -> assertThat(e.isShutdown()).isTrue());
        assertThat(handler.sessionsWithTurnExecutors()).isZero();

        finishTurn.countDown();
        assertThat(executors.getFirst().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(5, TimeUnit.SECONDS).until(() -> turnThreads.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void openingAndClosingManySessionsLeavesNoExecutorsOrThreadsBehind() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        when(chatChannel.conversationIds()).thenReturn(List.of("web"));
        when(chatChannel.loadHistoryAsHtml("web")).thenReturn(List.of());
        List<ExecutorService> executors = new CopyOnWriteArrayList<>();
        Set<Thread> turnThreads = ConcurrentHashMap.newKeySet();
        doAnswer(_ -> turnThreads.add(Thread.currentThread())).when(chatChannel).chat(anyString(), anyString());
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), () -> {
            ExecutorService executor = ChatWebSocketHandler.newTurnExecutor();
            executors.add(executor);
            return executor;
        });

        int sessions = 200;
        for (int i = 0; i < sessions; i++) {
            WebSocketSession session = mock(WebSocketSession.class);
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, userMessage("hello " + i));
            handler.handleTextMessage(session, userMessage("again " + i));
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }

        assertThat(handler.sessionsWithTurnExecutors()).isZero();
        assertThat(executors).hasSize(sessions);
        for (ExecutorService executor : executors) {
            assertThat(executor.isShutdown()).isTrue();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        // Every queued turn still ran before its executor stopped, then its thread ended
        verify(chatChannel, times(2 * sessions)).chat(anyString(), anyString());
        await().atMost(5, TimeUnit.SECONDS).until(() -> turnThreads.stream().noneMatch(Thread::isAlive));
    }

    private static TextMessage userMessage(String text) {
        return new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage", "conversationId", "web", "message", text)));
    }
}

package ai.javaclaw.chat.ws;

import ai.javaclaw.chat.ChatChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {

    @Test
    void handleUserMessageShowsNonTransientErrorAndClearsTypingIndicatorWhenAgentFails() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());

        doThrow(new RuntimeException("""
                HTTP 401 - {
                    "error": {
                        "message": "Incorrect API key provided: Test.",
                        "code": "invalid_api_key"
                    }
                }
                """)).when(chatChannel).chat("hello");

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "message", "hello"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).runCommand("hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        inOrder.verify(chatChannel).chat("hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        verifyNoMoreInteractions(chatChannel);

        assertThat(String.join("", htmlCaptor.getAllValues().get(0)))
                .contains("hello")
                .contains("typing-indicator")
                .contains("ar-typing");

        assertThat(String.join("", htmlCaptor.getAllValues().get(1)))
                .contains("ar-msg--agent ar-msg--error")
                .contains("An error occurred while contacting the AI provider")
                .contains("Details: HTTP 401 - {")
                .contains("typing-indicator")
                .doesNotContain("ar-typing");
    }

    @Test
    void handleUserMessageShowsGenericProviderErrorForUnexpectedFailures() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());

        doThrow(new RuntimeException("boom")).when(chatChannel).chat(anyString());

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "message", "hello"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).runCommand("hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        inOrder.verify(chatChannel).chat("hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());

        assertThat(String.join("", htmlCaptor.getAllValues().get(1)))
                .contains("An error occurred while contacting the AI provider")
                .contains("Details: boom");
    }

    @Test
    void handleUserMessageClearsTypingIndicatorWithoutAppendingBubbleWhenResponseWasStreamed() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());

        // the response is streamed to the client by ChatChannel while chat() runs

        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                "type", "userMessage",
                "message", "hello"
        ))));

        ArgumentCaptor<String[]> htmlCaptor = ArgumentCaptor.forClass(String[].class);
        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).runCommand("hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        inOrder.verify(chatChannel).chat("hello");
        inOrder.verify(chatChannel).sendHtml(htmlCaptor.capture());
        verifyNoMoreInteractions(chatChannel);

        assertThat(String.join("", htmlCaptor.getAllValues().get(1)))
                .contains("typing-indicator")
                .doesNotContain("streamed response")
                .doesNotContain("ar-msg--agent");
    }

    @Test
    void aCommandIsAnsweredWithoutEchoOrAgent() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());
        when(chatChannel.runCommand("/new")).thenReturn(true);

        handle(handler, Map.of("type", "userMessage", "message", "  /new  "));

        verify(chatChannel).runCommand("/new");
        verifyNoMoreInteractions(chatChannel);
    }

    @Test
    void sessionFramesGoToTheChatChannel() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());

        handle(handler, Map.of("type", "newChat"));
        handle(handler, Map.of("type", "switchSession", "sessionId", "s1"));
        handle(handler, Map.of("type", "viewSession", "sessionId", "telegram-42"));
        handle(handler, Map.of("type", "renameSession", "sessionId", "s1", "title", "Trip to Lisbon"));

        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).newChat();
        inOrder.verify(chatChannel).switchTo("s1");
        inOrder.verify(chatChannel).showChannel("telegram-42");
        inOrder.verify(chatChannel).rename("s1", "Trip to Lisbon");
        verifyNoMoreInteractions(chatChannel);
    }

    @Test
    void aRefusedSessionFrameChangesNothingAndKeepsTheConnection() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());
        doThrow(new IllegalArgumentException("not web's")).when(chatChannel).switchTo("telegram-42");

        handle(handler, Map.of("type", "switchSession", "sessionId", "telegram-42"));
        handle(handler, Map.of("type", "renameSession", "sessionId", "s1"));

        verify(chatChannel).switchTo("telegram-42");
        verifyNoMoreInteractions(chatChannel);
    }

    @Test
    void afterConnectionEstablishedShowsTheWebChat() throws Exception {
        ChatChannel chatChannel = mock(ChatChannel.class);
        WebSocketSession session = mock(WebSocketSession.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper());

        handler.afterConnectionEstablished(session);

        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).setWsSession(session);
        inOrder.verify(chatChannel).showWebChat();
        inOrder.verify(chatChannel).flushPendingMessages();
    }

    private static void handle(ChatWebSocketHandler handler, Map<String, Object> frame) throws Exception {
        handler.handleTextMessage(mock(WebSocketSession.class), new TextMessage(new ObjectMapper().writeValueAsString(frame)));
    }
}

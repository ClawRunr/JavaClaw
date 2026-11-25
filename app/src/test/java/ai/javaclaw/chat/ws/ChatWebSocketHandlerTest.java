package ai.javaclaw.chat.ws;

import ai.javaclaw.chat.ChatChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {

    private final ChatChannel chatChannel = mock(ChatChannel.class);
    private final WebSocketSession tab = mock(WebSocketSession.class);
    /** Answers queued here run when the test says so, as if on their own thread. */
    private final List<Runnable> answers = new ArrayList<>();
    private final Executor executor = answers::add;
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(chatChannel, new ObjectMapper(), executor);

    @Test
    void aMessageIsEchoedToItsSessionAndAnsweredOffTheSocketThread() throws Exception {
        when(chatChannel.isWebSession("s1")).thenReturn(true);

        handle(Map.of("type", "userMessage", "sessionId", "s1", "message", " hello "));

        ArgumentCaptor<String[]> echo = ArgumentCaptor.forClass(String[].class);
        verify(chatChannel).sendHtmlTo(eq("s1"), echo.capture());
        assertThat(String.join("", echo.getValue())).contains("hello").contains("typing-indicator").contains("ar-typing");
        verify(chatChannel, never()).chat(anyString(), anyString());

        answers.forEach(Runnable::run);

        verify(chatChannel).chat("s1", "hello");
    }

    @Test
    void aFailedAnswerIsReportedAsAnErrorFrameOfItsSession() throws Exception {
        when(chatChannel.isWebSession("s1")).thenReturn(true);
        doThrow(new RuntimeException("""
                HTTP 401 - {
                    "error": {
                        "message": "Incorrect API key provided: Test.",
                        "code": "invalid_api_key"
                    }
                }
                """)).when(chatChannel).chat("s1", "hello");

        handle(Map.of("type", "userMessage", "sessionId", "s1", "message", "hello"));
        answers.forEach(Runnable::run);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(chatChannel).failed(eq("s1"), error.capture());
        assertThat(error.getValue())
                .contains("An error occurred while contacting the AI provider")
                .contains("Details: HTTP 401 - {");
    }

    @Test
    void aMessageClaimingASessionThatIsNotTheWebChatsIsRefused() throws Exception {
        when(chatChannel.isWebSession("telegram-session")).thenReturn(false);

        handle(Map.of("type", "userMessage", "sessionId", "telegram-session", "message", "hello"));
        handle(Map.of("type", "userMessage", "message", "hello"));

        assertThat(answers).isEmpty();
        verify(chatChannel, never()).sendHtmlTo(anyString(), any(String[].class));
        verify(chatChannel, never()).chat(anyString(), anyString());
    }

    @Test
    void aCommandIsAnsweredWithoutEchoOrAgent() throws Exception {
        when(chatChannel.runCommand(tab, "/new")).thenReturn(true);

        handle(Map.of("type", "userMessage", "sessionId", "s1", "message", "  /new  "));

        verify(chatChannel).runCommand(tab, "/new");
        verifyNoMoreInteractions(chatChannel);
        assertThat(answers).isEmpty();
    }

    @Test
    void sessionFramesGoToTheChatChannelForThisTab() throws Exception {
        when(chatChannel.isWebSession("s1")).thenReturn(true);

        handle(Map.of("type", "newChat"));
        handle(Map.of("type", "switchSession", "sessionId", "s1"));
        handle(Map.of("type", "viewSession", "sessionId", "telegram-42"));
        handle(Map.of("type", "renameSession", "sessionId", "s1", "title", "Trip to Lisbon"));

        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).newChat(tab);
        inOrder.verify(chatChannel).switchTo(tab, "s1");
        inOrder.verify(chatChannel).showChannel(tab, "telegram-42");
        inOrder.verify(chatChannel).rename("s1", "Trip to Lisbon");
    }

    @Test
    void aRenameOfASessionThatIsNotTheWebChatsIsRefused() throws Exception {
        when(chatChannel.isWebSession("telegram-42")).thenReturn(false);

        handle(Map.of("type", "renameSession", "sessionId", "telegram-42", "title", "Mine"));
        handle(Map.of("type", "renameSession", "sessionId", "s1"));

        verify(chatChannel, never()).rename(anyString(), anyString());
    }

    @Test
    void aRefusedSwitchChangesNothingAndKeepsTheConnection() throws Exception {
        doThrow(new IllegalArgumentException("not web's")).when(chatChannel).switchTo(tab, "telegram-42");

        handle(Map.of("type", "switchSession", "sessionId", "telegram-42"));

        verify(chatChannel).switchTo(tab, "telegram-42");
        verifyNoMoreInteractions(chatChannel);
    }

    @Test
    void aTabOpensAndClosesWithItsConnection() throws Exception {
        handler.afterConnectionEstablished(tab);
        handler.afterConnectionClosed(tab, CloseStatus.NORMAL);

        var inOrder = inOrder(chatChannel);
        inOrder.verify(chatChannel).open(tab);
        inOrder.verify(chatChannel).close(tab);
    }

    private void handle(Map<String, Object> frame) throws Exception {
        handler.handleTextMessage(tab, new TextMessage(new ObjectMapper().writeValueAsString(frame)));
    }
}

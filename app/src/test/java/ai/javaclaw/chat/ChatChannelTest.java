package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ChatHistory;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    @Mock ChatHistory chatHistory;

    ChatChannel chatChannel;

    @BeforeEach
    void setUp() {
        chatChannel = new ChatChannel(agent, new ChannelRegistry(), chatHistory, new ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // conversationIds
    // -----------------------------------------------------------------------

    @Test
    void conversationIdsAlwaysContainsWebFirst() {
        when(chatHistory.conversationIds()).thenReturn(List.of("telegram-42", "web"));

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids).first().isEqualTo("web");
    }

    @Test
    void conversationIdsIncludesWebEvenWhenRepositoryReturnsEmpty() {
        when(chatHistory.conversationIds()).thenReturn(List.of());

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids).containsExactly("web");
    }

    @Test
    void conversationIdsIncludesOtherChannelsAfterWeb() {
        when(chatHistory.conversationIds()).thenReturn(List.of("telegram-42", "telegram-99"));

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids).containsExactly("web", "telegram-42", "telegram-99");
    }

    @Test
    void conversationIdsDeduplicatesWeb() {
        when(chatHistory.conversationIds()).thenReturn(List.of("web", "telegram-42"));

        List<String> ids = chatChannel.conversationIds();

        assertThat(ids.stream().filter("web"::equals)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // loadHistoryAsHtml
    // -----------------------------------------------------------------------

    @Test
    void loadHistoryReturnsWelcomeBubbleWhenNoHistory() {
        when(chatHistory.turns("web")).thenReturn(List.of());

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles).hasSize(1);
        // The welcome message is not a stored message, so it has no time
        assertThat(bubbles.get(0)).contains("ar-msg--agent").doesNotContain("<time");
    }

    @Test
    void aTurnWithoutToolsIsAUserBubbleAndAnAgentBubbleAsBefore() {
        when(chatHistory.turns("web")).thenReturn(List.of(turn("Hello", "Hi there")));

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles).containsExactly(
                ChatHtml.userBubble("Hello", T0),
                ChatHtml.agentBubble("Hi there", T0.plusSeconds(1)));
        assertThat(bubbles.get(0)).contains("ar-msg--user").contains("<div class=\"ar-msg__bubble\">Hello</div>");
        // Agent text goes to the browser as Markdown source, rendered there like a streamed answer
        assertThat(bubbles.get(1)).contains("ar-msg--agent").contains("<div class=\"ar-md\" data-md>Hi there</div>")
                .doesNotContain("data-tools");
    }

    @Test
    void loadHistoryShowsTheStoredTimeOfEachMessageAsUtc() {
        when(chatHistory.turns("web")).thenReturn(List.of(new ChatHistory.Turn(
                "Hello", Instant.parse("2026-11-14T10:42:05.123456Z"), List.of(), "Hi there", Instant.parse("2026-11-14T10:43:00Z"))));

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles.get(0)).contains(
                "<div class=\"ar-msg__bubble\">Hello</div><time class=\"ar-msg__time\" datetime=\"2026-11-14T10:42:05Z\"></time>");
        assertThat(bubbles.get(1)).contains(
                "<span class=\"ar-msg__author\">JavaClaw</span><time class=\"ar-msg__time\" datetime=\"2026-11-14T10:43:00Z\"></time>");
    }

    @Test
    void loadHistoryEscapesHtmlInMessages() {
        when(chatHistory.turns("web")).thenReturn(List.of(turn("<script>alert('xss')</script>", "<img src=x onerror=alert(1)>")));

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles.get(0)).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(bubbles.get(1)).doesNotContain("<img src=x").contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void aTurnWithToolsIsOneAgentMessageWithTheCallsAsDataAndAllTheText() throws Exception {
        ChatHistory.ToolCall paris = new ChatHistory.ToolCall("call-1", "weather", "{\"city\":\"Paris\"}",
                T0.plusSeconds(1), "\"sunny\"", T0.plusMillis(3250));
        ChatHistory.ToolCall rome = new ChatHistory.ToolCall("call-2", "weather", "{\"city\":\"Rome\"}",
                T0.plusSeconds(4), null, null);
        when(chatHistory.turns("web")).thenReturn(List.of(new ChatHistory.Turn(
                "Weather?", T0, List.of(paris, rome), "Checking.\n\nParis is sunny.", T0.plusSeconds(1))));

        List<String> bubbles = chatChannel.loadHistoryAsHtml("web");

        assertThat(bubbles).hasSize(2);
        String agent = bubbles.get(1);
        assertThat(agent).contains("<div class=\"ar-md\" data-md>Checking.\n\nParis is sunny.</div>");
        // No tool card HTML from the server: chat.js builds the cards from the data
        assertThat(agent).doesNotContain("ar-tool");
        assertThat(toolsData(agent)).containsExactly(
                Map.of("id", "call-1", "name", "weather", "input", "{\"city\":\"Paris\"}",
                        "calledAt", "2026-11-14T10:42:06Z", "output", "\"sunny\"", "resultAt", "2026-11-14T10:42:08.250Z"),
                // A call without a result has neither output nor result time
                Map.of("id", "call-2", "name", "weather", "input", "{\"city\":\"Rome\"}",
                        "calledAt", "2026-11-14T10:42:09Z"));
    }

    @Test
    void aLongToolOutputIsCutAndMarked() throws Exception {
        String output = "x".repeat(50_000);
        ChatHistory.ToolCall call = new ChatHistory.ToolCall("call-1", "read", "{}", T0, output, T0.plusSeconds(1));
        when(chatHistory.turns("web")).thenReturn(List.of(new ChatHistory.Turn("Read it", T0, List.of(call), "", T0)));

        assertThat(toolsData(chatChannel.loadHistoryAsHtml("web").get(1))).singleElement().satisfies(tool -> {
            assertThat(tool.get("output")).isEqualTo("x".repeat(20_000));
            assertThat(tool).containsEntry("truncated", true);
        });
    }

    @Test
    void aTurnWithOnlyToolCallsHasNoTextElement() {
        ChatHistory.ToolCall call = new ChatHistory.ToolCall("call-1", "weather", "{}", T0.plusSeconds(1), null, null);
        when(chatHistory.turns("web")).thenReturn(List.of(new ChatHistory.Turn("Weather?", T0, List.of(call), "", T0.plusSeconds(1))));

        assertThat(chatChannel.loadHistoryAsHtml("web").get(1)).contains("data-tools=").doesNotContain("data-md");
    }

    @Test
    void toolArgumentsAndResultsWithHtmlAreEscapedInTheAttribute() throws Exception {
        String input = "{\"html\":\"<img src=x onerror=alert(1)>\"}";
        String output = "<script>alert('out')</script> & \"quotes\"";
        ChatHistory.ToolCall call = new ChatHistory.ToolCall("call-1", "fetch", input, T0, output, T0.plusSeconds(1));
        when(chatHistory.turns("web")).thenReturn(List.of(new ChatHistory.Turn("Hi", T0, List.of(call), "", T0)));

        String agent = chatChannel.loadHistoryAsHtml("web").get(1);

        assertThat(agent).doesNotContain("<img src=x").doesNotContain("<script")
                .contains("&lt;img src=x").contains("&lt;script&gt;");
        assertThat(toolsData(agent)).singleElement().satisfies(tool -> {
            assertThat(tool.get("input")).isEqualTo(input);
            assertThat(tool.get("output")).isEqualTo(output);
        });
    }

    @Test
    void aTurnWithoutAnAnswerIsOnlyTheUserMessage() {
        when(chatHistory.turns("web")).thenReturn(List.of(new ChatHistory.Turn("Hello", T0, List.of(), "", null)));

        assertThat(chatChannel.loadHistoryAsHtml("web")).containsExactly(ChatHtml.userBubble("Hello", T0));
    }

    @Test
    void loadHistoryUsesSuppliedConversationId() {
        when(chatHistory.turns("telegram-42")).thenReturn(List.of());

        chatChannel.loadHistoryAsHtml("telegram-42");

        verify(chatHistory).turns("telegram-42");
    }

    // -----------------------------------------------------------------------
    // chat
    // -----------------------------------------------------------------------

    @Test
    void chatDelegatesToAgentWithConversationId() {
        chatChannel.chat("web", "hello");

        verify(agent).respondTo(eq(AgentRequest.chat("web", SessionChannel.WEB, "hello")), anyConsumer());
    }

    @Test
    void chatUsesSuppliedConversationId() {
        chatChannel.chat("telegram-42", "hello");

        verify(agent).respondTo(eq(AgentRequest.chat("telegram-42", SessionChannel.WEB, "hello")), anyConsumer());
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
        // The page shows the first frame's time as the answer's time
        assertThat(Instant.parse((String) frames.get(0).get("time")))
                .isBetween(Instant.now().minusSeconds(60), Instant.now());
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

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<AgentEvent> anyConsumer() {
        return any(java.util.function.Consumer.class);
    }

    private static final Instant T0 = Instant.parse("2026-11-14T10:42:05Z");

    private static ChatHistory.Turn turn(String question, String answer) {
        return new ChatHistory.Turn(question, T0, List.of(), answer, T0.plusSeconds(1));
    }

    /** The tool calls in an agent message's data-tools attribute, as the browser reads them. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolsData(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-tools=\"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as("data-tools attribute").isTrue();
        String json = org.springframework.web.util.HtmlUtils.htmlUnescape(m.group(1));
        return new ObjectMapper().readValue(json, List.class);
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        chatChannel.setWsSession(session);
        return session;
    }

    private void agentStreams(java.util.function.Consumer<java.util.function.Consumer<AgentEvent>> progress) {
        doAnswer(invocation -> {
            progress.accept(invocation.getArgument(1));
            return null;
        }).when(agent).respondTo(eq(AgentRequest.chat("web", SessionChannel.WEB, "hello")), anyConsumer());
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

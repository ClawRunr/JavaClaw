package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ChatCommands;
import ai.javaclaw.agent.ChatHistory;
import ai.javaclaw.agent.ChatSessions;
import ai.javaclaw.agent.ConversationKey;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.agent.SessionKind;
import ai.javaclaw.agent.SessionTitles;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatChannelTest {

    private static final ConversationKey WEB = ConversationKey.web();
    private static final Instant NOW = Instant.parse("2026-11-24T15:30:12Z");

    @Mock Agent agent;
    @Mock ChatHistory chatHistory;
    @Mock ChatSessions chatSessions;

    ChatChannel chatChannel;

    @BeforeEach
    void setUp() {
        chatChannel = new ChatChannel(agent, new ChannelRegistry(), chatSessions, new ChatCommands(chatSessions),
                chatHistory, new ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // Views: the web chat's sessions and the other channels' conversations
    // -----------------------------------------------------------------------

    @Test
    void showWebChatSendsTheActiveSessionsHeaderSidebarHistoryAndComposer() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s2", "Trip to Lisbon", true), summary("s1", "Groceries", false));
        when(chatSessions.otherConversations(WEB)).thenReturn(List.of(
                new ChatSessions.Summary("telegram-42", "Weather", SessionChannel.TELEGRAM, NOW, true)));
        when(chatHistory.turns("s2")).thenReturn(List.of(question("Lisbon in May?")));

        chatChannel.showWebChat();

        String html = sentHtml(session);
        assertThat(html)
                .contains("<span class=\"ar-chat-head__label\">Web</span>")
                .contains("<h1 class=\"ar-chat-head__title\" id=\"chat-title\">Trip to Lisbon</h1>")
                .contains("id=\"rename-btn\"")
                .contains("<input type=\"hidden\" name=\"sessionId\" value=\"s2\">")
                .contains("Lisbon in May?")
                .contains("<textarea id=\"message-input\"");
        // The sidebar: the web chat's sessions, the shown one highlighted, then the other channels
        assertThat(html.indexOf("Trip to Lisbon</span>")).isLessThan(html.indexOf("Groceries</span>"));
        assertThat(html)
                .contains("class=\"ar-side__item is-active\" aria-current=\"true\" ws-send hx-vals='{\"type\": \"switchSession\"}' name=\"sessionId\" value=\"s2\"")
                .contains("class=\"ar-side__item\" ws-send hx-vals='{\"type\": \"switchSession\"}' name=\"sessionId\" value=\"s1\"")
                .contains("hx-vals='{\"type\": \"viewSession\"}' name=\"sessionId\" value=\"telegram-42\"><span class=\"ar-side__channel\">Telegram</span><span class=\"ar-side__title\">Weather</span>")
                .contains("<time class=\"ar-msg__time\" datetime=\"2026-11-24T15:30:12Z\"></time>");
    }

    @Test
    void aNewSessionShowsTheWelcomeMessage() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s1", "New chat", true));

        chatChannel.showWebChat();

        assertThat(sentHtml(session)).contains("How can I help you today?").contains(">New chat</h1>");
    }

    @Test
    void newChatCreatesASessionAndShowsIt() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s3", "New chat", true));

        chatChannel.newChat();

        verify(chatSessions).create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        assertThat(sentHtml(session)).contains("How can I help you today?");
    }

    @Test
    void switchingLoadsThatSessionsHistory() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s1", "Groceries", true), summary("s2", "Trip to Lisbon", false));
        when(chatHistory.turns("s1")).thenReturn(List.of(question("Milk and eggs")));

        chatChannel.switchTo("s1");

        verify(chatSessions).switchTo(WEB, "s1");
        assertThat(sentHtml(session)).contains("Milk and eggs").contains(">Groceries</h1>");
    }

    @Test
    void switchingToASessionOfAnotherConversationIsRefused() {
        doThrow(new IllegalArgumentException("not web's")).when(chatSessions).switchTo(WEB, "telegram-42");

        assertThatThrownBy(() -> chatChannel.switchTo("telegram-42")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renameUpdatesTheHeaderAndTheSidebarButNotTheMessages() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s1", "Trip to Lisbon", true));

        chatChannel.rename("s1", "Trip to Lisbon");

        verify(chatSessions).rename(WEB, "s1", "Trip to Lisbon");
        assertThat(sentHtml(session))
                .contains("id=\"chat-header\"")
                .contains(">Trip to Lisbon</h1>")
                .contains("id=\"chat-sessions\"")
                .contains("<span class=\"ar-side__title\">Trip to Lisbon</span>")
                .doesNotContain("id=\"chat-messages\"");
    }

    @Test
    void aTitleSetInTheBackgroundShowsInTheHeaderAndSidebar() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s1", "Weekend in Lisbon", true));

        chatChannel.onSessionTitled(new SessionTitles.SessionTitled(WEB, "s1", "Weekend in Lisbon"));

        assertThat(sentHtml(session)).contains(">Weekend in Lisbon</h1>").doesNotContain("id=\"chat-messages\"");
    }

    @Test
    void anotherChannelsConversationIsShownReadOnlyWithoutRename() throws IOException {
        WebSocketSession session = openSession();
        when(chatSessions.summary("telegram-42"))
                .thenReturn(new ChatSessions.Summary("telegram-42", "Weather", SessionChannel.TELEGRAM, NOW, false));
        when(chatHistory.turns("telegram-42")).thenReturn(List.of(question("Rain tomorrow?")));

        chatChannel.showChannel("telegram-42");

        assertThat(sentHtml(session))
                .contains("<span class=\"ar-chat-head__label\">Telegram</span>")
                .contains(">Weather</h1>")
                .doesNotContain("rename-btn")
                .contains("Rain tomorrow?")
                .contains("chat-readonly-notice")
                .doesNotContain("<textarea");
    }

    // -----------------------------------------------------------------------
    // /new in the web chat
    // -----------------------------------------------------------------------

    @Test
    void newIsACommandThatNeverReachesTheAgent() throws IOException {
        WebSocketSession session = openSession();
        givenWebSessions(summary("s9", "Holiday", true));

        assertThat(chatChannel.runCommand("/new Holiday")).isTrue();

        verify(chatSessions).create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Holiday");
        verifyNoInteractions(agent);
        assertThat(sentHtml(session)).contains("Started a new conversation.").contains(">Holiday</h1>")
                .doesNotContain("How can I help you today?");
    }

    @Test
    void anythingElseIsNotACommand() throws IOException {
        assertThat(chatChannel.runCommand("/news please")).isFalse();

        verify(chatSessions, never()).create(any(), any(), any(), any());
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
    void loadHistoryUsesSuppliedSessionId() {
        when(chatHistory.turns("telegram-42")).thenReturn(List.of());

        chatChannel.loadHistoryAsHtml("telegram-42");

        verify(chatHistory).turns("telegram-42");
    }

    // -----------------------------------------------------------------------
    // chat
    // -----------------------------------------------------------------------

    @Test
    void chatDelegatesToAgentInTheWebConversation() {
        chatChannel.chat("hello");

        verify(agent).respondTo(eq(AgentRequest.chat(WEB, SessionChannel.WEB, "hello")), anyConsumer());
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

        chatChannel.chat("hello");

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

        chatChannel.chat("hello");

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

        chatChannel.chat("hello");

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
        chatChannel.chat("hello");
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<AgentEvent> anyConsumer() {
        return any(java.util.function.Consumer.class);
    }

    private static final Instant T0 = Instant.parse("2026-11-14T10:42:05Z");

    private static ChatHistory.Turn turn(String question, String answer) {
        return new ChatHistory.Turn(question, T0, List.of(), answer, T0.plusSeconds(1));
    }

    /** A turn with a question and no answer yet. */
    private static ChatHistory.Turn question(String question) {
        return new ChatHistory.Turn(question, T0, List.of(), "", null);
    }

    /** The tool calls in an agent message's data-tools attribute, as the browser reads them. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolsData(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-tools=\"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as("data-tools attribute").isTrue();
        String json = org.springframework.web.util.HtmlUtils.htmlUnescape(m.group(1));
        return new ObjectMapper().readValue(json, List.class);
    }

    private void givenWebSessions(ChatSessions.Summary... sessions) {
        ChatSessions.Summary active = java.util.Arrays.stream(sessions).filter(ChatSessions.Summary::active).findFirst().orElseThrow();
        lenient().when(chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB)).thenReturn(active.id());
        lenient().when(chatSessions.list(WEB)).thenReturn(List.of(sessions));
    }

    private static ChatSessions.Summary summary(String id, String title, boolean active) {
        return new ChatSessions.Summary(id, title, SessionChannel.WEB, NOW, active);
    }

    /** Everything sent to the browser as HTML, in one string. */
    private static String sentHtml(WebSocketSession session) throws IOException {
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(messageCaptor.capture());
        return messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);
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
        }).when(agent).respondTo(eq(AgentRequest.chat(WEB, SessionChannel.WEB, "hello")), anyConsumer());
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

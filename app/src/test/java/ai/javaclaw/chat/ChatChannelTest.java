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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The web chat channel in one or more browser tabs, each a fake WebSocket session that records
 * what it is sent, on Spring AI's in-memory session store with a mocked agent.
 */
class ChatChannelTest {

    private static final ConversationKey WEB = ConversationKey.web();
    private static final ConversationKey TELEGRAM = ConversationKey.telegram(42, null);

    private final InMemorySessionRepository repository = InMemorySessionRepository.builder().build();
    private final SessionService sessionService = DefaultSessionService.builder().sessionRepository(repository).build();
    private final ChatSessions chatSessions = new ChatSessions(sessionService, repository);
    private final Agent agent = mock(Agent.class);
    private final ChatChannel chatChannel = new ChatChannel(agent, new ChannelRegistry(), chatSessions,
            new ChatCommands(chatSessions), new ChatHistory(sessionService), new ObjectMapper());

    // -----------------------------------------------------------------------
    // Tabs and views
    // -----------------------------------------------------------------------

    @Test
    void aNewTabShowsTheWebConversationsActiveSession() throws IOException {
        chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Groceries");
        String active = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Trip to Lisbon");
        Tab tab = new Tab("tab-1");

        chatChannel.open(tab.socket);

        assertThat(tab.shownSessionId()).isEqualTo(active);
        assertThat(tab.html())
                .contains("<span class=\"ar-chat-head__label\">Web</span>")
                .contains("<h1 class=\"ar-chat-head__title\" id=\"chat-title\">Trip to Lisbon</h1>")
                .contains("id=\"rename-btn\"")
                .contains("<input type=\"hidden\" name=\"sessionId\" value=\"" + active + "\">")
                .contains("<textarea id=\"message-input\"")
                .contains("How can I help you today?");
        // The sidebar: the web chat's sessions, the shown one highlighted
        assertThat(tab.html())
                .contains("class=\"ar-side__item is-active\" aria-current=\"true\" ws-send hx-vals='{\"type\": \"switchSession\"}' name=\"sessionId\" value=\"" + active + "\"")
                .contains("<span class=\"ar-side__title\">Groceries</span>");
    }

    @Test
    void theFirstTabCreatesTheFirstSession() throws IOException {
        Tab tab = new Tab("tab-1");

        chatChannel.open(tab.socket);

        assertThat(tab.shownSessionId()).isEqualTo(chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB));
        assertThat(tab.html()).contains(">New chat</h1>");
    }

    @Test
    void switchingShowsThatSessionsHistoryInThisTabOnlyAndMakesItTheDefaultForNewTabs() throws IOException {
        String groceries = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Groceries");
        remember(groceries, new UserMessage("Milk and eggs"));
        String lisbon = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Trip to Lisbon");
        Tab first = open("tab-1");
        Tab second = open("tab-2");

        chatChannel.switchTo(first.socket, groceries);

        assertThat(first.shownSessionId()).isEqualTo(groceries);
        assertThat(first.lastView()).contains("Milk and eggs").contains(">Groceries</h1>");
        assertThat(second.shownSessionId()).isEqualTo(lisbon);
        assertThat(open("tab-3").shownSessionId()).isEqualTo(groceries);
    }

    @Test
    void switchingToASessionOfAnotherConversationIsRefused() throws IOException {
        String telegram = chatSessions.active(TELEGRAM, SessionKind.CHAT, SessionChannel.TELEGRAM);
        Tab tab = open("tab-1");
        String shown = tab.shownSessionId();

        assertThatThrownBy(() -> chatChannel.switchTo(tab.socket, telegram)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tab.shownSessionId()).isEqualTo(shown);
    }

    @Test
    void newChatShowsTheNewSessionInThisTabAndOtherTabsKeepWhatTheyShow() throws IOException {
        Tab first = open("tab-1");
        Tab second = open("tab-2");
        String before = second.shownSessionId();

        chatChannel.newChat(first.socket);

        assertThat(first.shownSessionId()).isNotEqualTo(before)
                .isEqualTo(chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB));
        assertThat(first.lastView()).contains("How can I help you today?");
        assertThat(second.shownSessionId()).isEqualTo(before);
        // The other tab's sidebar lists the new chat
        assertThat(second.lastMessage()).contains("value=\"" + first.shownSessionId() + "\"").doesNotContain("id=\"chat-messages\"");
    }

    @Test
    void newTypedInOneTabStartsANamedSessionThereAndNeverReachesTheAgent() throws IOException {
        Tab first = open("tab-1");
        Tab second = open("tab-2");
        String before = second.shownSessionId();

        assertThat(chatChannel.runCommand(first.socket, "/new Holiday")).isTrue();

        assertThat(first.shownSessionId()).isNotEqualTo(before);
        assertThat(first.lastView()).contains("Started a new conversation.").contains(">Holiday</h1>")
                .doesNotContain("How can I help you today?");
        assertThat(second.shownSessionId()).isEqualTo(before);
        verify(agent, never()).respondTo(any(AgentRequest.class), any());
    }

    @Test
    void anythingElseIsNotACommand() throws IOException {
        Tab tab = open("tab-1");

        assertThat(chatChannel.runCommand(tab.socket, "/news please")).isFalse();

        assertThat(chatSessions.list(WEB)).hasSize(1);
    }

    @Test
    void aRenameShowsInEveryTab() throws IOException {
        Tab first = open("tab-1");
        Tab second = open("tab-2");
        String shared = first.shownSessionId();

        chatChannel.rename(shared, "Trip to Lisbon");

        assertThat(first.lastMessage()).contains(">Trip to Lisbon</h1>").contains("<span class=\"ar-side__title\">Trip to Lisbon</span>")
                .doesNotContain("id=\"chat-messages\"");
        assertThat(second.lastMessage()).contains(">Trip to Lisbon</h1>").contains("<span class=\"ar-side__title\">Trip to Lisbon</span>");
    }

    @Test
    void aTitleSetInTheBackgroundShowsInEveryTab() throws IOException {
        Tab first = open("tab-1");
        String other = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        Tab second = open("tab-2");
        chatSessions.rename(WEB, other, "Weekend in Lisbon");

        chatChannel.onSessionTitled(new SessionTitles.SessionTitled(WEB, other, "Weekend in Lisbon"));

        assertThat(first.lastMessage()).contains("<span class=\"ar-side__title\">Weekend in Lisbon</span>");
        assertThat(second.lastMessage()).contains(">Weekend in Lisbon</h1>");
    }

    @Test
    void anotherChannelsConversationIsShownReadOnlyWithoutRename() throws IOException {
        String telegram = chatSessions.active(TELEGRAM, SessionKind.CHAT, SessionChannel.TELEGRAM);
        chatSessions.rename(TELEGRAM, telegram, "Weather");
        remember(telegram, new UserMessage("Rain tomorrow?"));
        Tab tab = open("tab-1");

        chatChannel.showChannel(tab.socket, telegram);

        assertThat(tab.shownSessionId()).isEqualTo(telegram);
        assertThat(tab.lastView())
                .contains("<span class=\"ar-chat-head__label\">Telegram</span>")
                .contains(">Weather</h1>")
                .doesNotContain("rename-btn")
                .contains("Rain tomorrow?")
                .contains("chat-readonly-notice")
                .doesNotContain("<textarea");
    }

    // -----------------------------------------------------------------------
    // Messages go to the session the tab shows
    // -----------------------------------------------------------------------

    @Test
    void twoTabsOnDifferentSessionsEachSendToTheSessionTheyShow() throws IOException {
        String groceries = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Groceries");
        Tab first = open("tab-1");
        String lisbon = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Trip to Lisbon");
        Tab second = open("tab-2");
        assertThat(first.shownSessionId()).isEqualTo(groceries);
        assertThat(second.shownSessionId()).isEqualTo(lisbon);

        chatChannel.chat(first.shownSessionId(), "milk?");
        chatChannel.chat(second.shownSessionId(), "museums?");

        verify(agent).respondTo(eq(AgentRequest.chat(WEB, groceries, SessionChannel.WEB, "milk?")), any());
        verify(agent).respondTo(eq(AgentRequest.chat(WEB, lisbon, SessionChannel.WEB, "museums?")), any());
    }

    @Test
    void aMessageClaimingASessionOfAnotherConversationIsRefused() {
        String telegram = chatSessions.active(TELEGRAM, SessionKind.CHAT, SessionChannel.TELEGRAM);

        assertThatThrownBy(() -> chatChannel.chat(telegram, "hello")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chatChannel.chat("no-such-session", "hello")).isInstanceOf(IllegalArgumentException.class);
        verify(agent, never()).respondTo(any(AgentRequest.class), any());
    }

    // -----------------------------------------------------------------------
    // Stream frames stay with their session
    // -----------------------------------------------------------------------

    @Test
    void streamFramesCarryTheSessionIdAndGoOnlyToTabsShowingIt() throws IOException {
        Tab first = open("tab-1");
        String streamed = first.shownSessionId();
        Tab sameSession = open("tab-2");
        chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        Tab otherSession = open("tab-3");
        agentStreams(events -> {
            events.accept(new AgentEvent.Token("Hello "));
            events.accept(new AgentEvent.ToolCall("call-1", "readFile", "{\"path\":\"pom.xml\"}"));
            events.accept(new AgentEvent.ToolResult("call-1", "readFile", "file contents"));
            events.accept(new AgentEvent.Done());
        });

        chatChannel.chat(streamed, "hello");

        List<Map<String, Object>> frames = first.frames();
        assertThat(frames).extracting(frame -> frame.get("type")).containsExactly("chunk", "toolCall", "toolResult", "done");
        assertThat(frames).allSatisfy(frame -> assertThat(frame).containsEntry("sessionId", streamed));
        assertThat(frames.get(0)).containsEntry("data", "Hello ");
        assertThat(frames.get(1)).containsEntry("data", Map.of("id", "call-1", "name", "readFile", "input", "{\"path\":\"pom.xml\"}"));
        // The page shows the first frame's time as the answer's time
        assertThat(Instant.parse((String) frames.get(0).get("time"))).isBetween(Instant.now().minusSeconds(60), Instant.now());
        assertThat(sameSession.frames()).hasSize(4);
        assertThat(otherSession.frames()).isEmpty();
    }

    @Test
    void aTabThatSwitchesAwayMidStreamGetsNoMoreOfIt() throws IOException {
        Tab tab = open("tab-1");
        String streamed = tab.shownSessionId();
        String other = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        agentStreams(events -> {
            events.accept(new AgentEvent.Token("Hello "));
            try {
                chatChannel.switchTo(tab.socket, other);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            events.accept(new AgentEvent.Token("world"));
            events.accept(new AgentEvent.Done());
        });

        chatChannel.chat(streamed, "hello");

        assertThat(tab.frames()).extracting(frame -> frame.get("data")).containsExactly("Hello ");
    }

    @Test
    void aTurnThatFailsBeforeStreamingReportsAnErrorFrameToItsSession() throws IOException {
        Tab tab = open("tab-1");

        chatChannel.failed(tab.shownSessionId(), "boom");

        assertThat(tab.frames()).singleElement().satisfies(frame -> assertThat(frame)
                .containsEntry("type", "error").containsEntry("data", "boom").containsEntry("sessionId", tab.shownSessionId()));
    }

    @Test
    void theEchoOfAQuestionGoesToTheTabsShowingItsSession() throws IOException {
        Tab first = open("tab-1");
        Tab same = open("tab-2");
        chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        Tab other = open("tab-3");

        chatChannel.sendHtmlTo(first.shownSessionId(), "<div>echo</div>");

        assertThat(first.lastMessage()).isEqualTo("<div>echo</div>");
        assertThat(same.lastMessage()).isEqualTo("<div>echo</div>");
        assertThat(other.html()).doesNotContain("echo");
    }

    // -----------------------------------------------------------------------
    // History
    // -----------------------------------------------------------------------

    @Test
    void loadHistoryReturnsWelcomeBubbleWhenNoHistory() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);

        List<String> bubbles = chatChannel.loadHistoryAsHtml(id);

        assertThat(bubbles).hasSize(1);
        // The welcome message is not a stored message, so it has no time
        assertThat(bubbles.get(0)).contains("ar-msg--agent").doesNotContain("<time");
    }

    @Test
    void aTurnWithoutToolsIsAUserBubbleAndAnAgentBubbleAsBefore() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Hello"), T0);
        remember(id, new AssistantMessage("Hi there"), T0.plusSeconds(1));

        List<String> bubbles = chatChannel.loadHistoryAsHtml(id);

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
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Hello"), Instant.parse("2026-11-14T10:42:05.123456Z"));
        remember(id, new AssistantMessage("Hi there"), Instant.parse("2026-11-14T10:43:00Z"));

        List<String> bubbles = chatChannel.loadHistoryAsHtml(id);

        assertThat(bubbles.get(0)).contains(
                "<div class=\"ar-msg__bubble\">Hello</div><time class=\"ar-msg__time\" datetime=\"2026-11-14T10:42:05Z\"></time>");
        assertThat(bubbles.get(1)).contains(
                "<span class=\"ar-msg__author\">JavaClaw</span><time class=\"ar-msg__time\" datetime=\"2026-11-14T10:43:00Z\"></time>");
    }

    @Test
    void loadHistoryEscapesHtmlInMessages() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("<script>alert('xss')</script>"));
        remember(id, new AssistantMessage("<img src=x onerror=alert(1)>"));

        List<String> bubbles = chatChannel.loadHistoryAsHtml(id);

        assertThat(bubbles.get(0)).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(bubbles.get(1)).doesNotContain("<img src=x").contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void aTurnWithToolsIsOneAgentMessageWithTheCallsAsDataAndAllTheText() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Weather?"), T0);
        remember(id, toolCalls("Checking.", call("call-1", "weather", "{\"city\":\"Paris\"}")), T0.plusSeconds(1));
        remember(id, results(result("call-1", "weather", "\"sunny\"")), T0.plusMillis(3250));
        remember(id, toolCalls("", call("call-2", "weather", "{\"city\":\"Rome\"}")), T0.plusSeconds(4));
        remember(id, new AssistantMessage("Paris is sunny."), T0.plusSeconds(5));

        List<String> bubbles = chatChannel.loadHistoryAsHtml(id);

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
    void aLongToolOutputIsCutAndMarked() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Read it"), T0);
        remember(id, toolCalls("", call("call-1", "read", "{}")), T0);
        remember(id, results(result("call-1", "read", "x".repeat(50_000))), T0.plusSeconds(1));

        assertThat(toolsData(chatChannel.loadHistoryAsHtml(id).get(1))).singleElement().satisfies(tool -> {
            assertThat(tool.get("output")).isEqualTo("x".repeat(20_000));
            assertThat(tool).containsEntry("truncated", true);
        });
    }

    @Test
    void aTurnWithOnlyToolCallsHasNoTextElement() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Weather?"), T0);
        remember(id, toolCalls("", call("call-1", "weather", "{}")), T0.plusSeconds(1));

        assertThat(chatChannel.loadHistoryAsHtml(id).get(1)).contains("data-tools=").doesNotContain("data-md");
    }

    @Test
    void toolArgumentsAndResultsWithHtmlAreEscapedInTheAttribute() {
        String input = "{\"html\":\"<img src=x onerror=alert(1)>\"}";
        String output = "<script>alert('out')</script> & \"quotes\"";
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Hi"), T0);
        remember(id, toolCalls("", call("call-1", "fetch", input)), T0);
        remember(id, results(result("call-1", "fetch", output)), T0.plusSeconds(1));

        String agent = chatChannel.loadHistoryAsHtml(id).get(1);

        assertThat(agent).doesNotContain("<img src=x").doesNotContain("<script")
                .contains("&lt;img src=x").contains("&lt;script&gt;");
        assertThat(toolsData(agent)).singleElement().satisfies(tool -> {
            assertThat(tool.get("input")).isEqualTo(input);
            assertThat(tool.get("output")).isEqualTo(output);
        });
    }

    @Test
    void aTurnWithoutAnAnswerIsOnlyTheUserMessage() {
        String id = chatSessions.active(WEB, SessionKind.CHAT, SessionChannel.WEB);
        remember(id, new UserMessage("Hello"), T0);

        assertThat(chatChannel.loadHistoryAsHtml(id)).containsExactly(ChatHtml.userBubble("Hello", T0));
    }

    @Test
    void everyViewCarriesTheToolCallsOfTheSessionItShowsOnly() throws IOException {
        String paris = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Paris");
        remember(paris, new UserMessage("Weather in Paris?"), T0);
        remember(paris, toolCalls("", call("call-p", "weather", "{\"city\":\"Paris\"}")), T0);
        String rome = chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, "Rome");
        remember(rome, new UserMessage("Weather in Rome?"), T0);
        remember(rome, toolCalls("", call("call-r", "weather", "{\"city\":\"Rome\"}")), T0);
        String telegram = chatSessions.active(TELEGRAM, SessionKind.CHAT, SessionChannel.TELEGRAM);
        remember(telegram, new UserMessage("Weather in Oslo?"), T0);
        remember(telegram, toolCalls("", call("call-o", "weather", "{\"city\":\"Oslo\"}")), T0);

        // On load: the active session, Rome
        Tab tab = open("tab-1");
        assertThat(toolsData(tab.lastView())).extracting(tool -> tool.get("id")).containsExactly("call-r");

        chatChannel.switchTo(tab.socket, paris);
        assertThat(toolsData(tab.lastView())).extracting(tool -> tool.get("id")).containsExactly("call-p");

        chatChannel.showChannel(tab.socket, telegram);
        assertThat(toolsData(tab.lastView())).extracting(tool -> tool.get("id")).containsExactly("call-o");
    }

    // -----------------------------------------------------------------------
    // Background messages
    // -----------------------------------------------------------------------

    @Test
    void aBackgroundMessageGoesToEveryTab() throws IOException {
        Tab first = open("tab-1");
        chatSessions.create(WEB, SessionKind.CHAT, SessionChannel.WEB, null);
        Tab second = open("tab-2");

        chatChannel.sendMessage("Task done");

        assertThat(first.lastMessage()).contains("Task done").contains("hx-swap-oob=\"beforeend\"");
        assertThat(second.lastMessage()).contains("Task done");
    }

    @Test
    void aBackgroundMessageWithoutTabsWaitsForTheNextOne() throws IOException {
        chatChannel.sendMessage("Task done");

        Tab tab = open("tab-1");

        assertThat(tab.lastMessage()).contains("Task done");
    }

    @Test
    void aClosedTabGetsNothingMore() throws IOException {
        Tab tab = open("tab-1");
        int sent = tab.payloads.size();

        chatChannel.close(tab.socket);
        chatChannel.sendMessage("Task done");

        assertThat(tab.payloads).hasSize(sent);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Tab open(String id) throws IOException {
        Tab tab = new Tab(id);
        chatChannel.open(tab.socket);
        return tab;
    }

    private void remember(String sessionId, Message message) {
        sessionService.appendMessage(sessionId, message);
    }

    private void remember(String sessionId, Message message, Instant time) {
        sessionService.appendEvent(SessionEvent.builder().sessionId(sessionId).timestamp(time).message(message).build());
    }

    private static final Instant T0 = Instant.parse("2026-11-14T10:42:05Z");

    private static AssistantMessage toolCalls(String text, AssistantMessage.ToolCall... calls) {
        return AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build();
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static ToolResponseMessage results(ToolResponseMessage.ToolResponse... responses) {
        return ToolResponseMessage.builder().responses(List.of(responses)).build();
    }

    private static ToolResponseMessage.ToolResponse result(String id, String name, String data) {
        return new ToolResponseMessage.ToolResponse(id, name, data);
    }

    /** The tool calls in the one agent message with a data-tools attribute, as the browser reads them. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolsData(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-tools=\"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as("data-tools attribute").isTrue();
        String json = org.springframework.web.util.HtmlUtils.htmlUnescape(m.group(1));
        assertThat(m.find()).as("a second data-tools attribute").isFalse();
        return new ObjectMapper().readValue(json, List.class);
    }

    private void agentStreams(Consumer<Consumer<AgentEvent>> progress) {
        doAnswer(invocation -> {
            progress.accept(invocation.getArgument(1));
            return null;
        }).when(agent).respondTo(any(AgentRequest.class), any());
    }

    /** A browser tab: a WebSocket session that records everything it is sent. */
    private static final class Tab {

        final WebSocketSession socket = mock(WebSocketSession.class);
        final List<String> payloads = new CopyOnWriteArrayList<>();

        Tab(String id) throws IOException {
            when(socket.getId()).thenReturn(id);
            when(socket.isOpen()).thenReturn(true);
            doAnswer(invocation -> {
                payloads.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(socket).sendMessage(any());
        }

        /** Everything sent as HTML, in one string. */
        String html() {
            return String.join("", payloads.stream().filter(payload -> !payload.startsWith("{")).toList());
        }

        String lastMessage() {
            return payloads.getLast();
        }

        /** The last full view the tab was sent. */
        String lastView() {
            return payloads.stream().filter(payload -> payload.contains("id=\"chat-messages\" data-session-id=")).toList().getLast();
        }

        /** The session the tab shows, as the last view marked it. */
        String shownSessionId() {
            String view = lastView();
            int start = view.indexOf("data-session-id=\"") + "data-session-id=\"".length();
            return view.substring(start, view.indexOf('"', start));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames() {
            ObjectMapper objectMapper = new ObjectMapper();
            return payloads.stream()
                    .filter(payload -> payload.startsWith("{"))
                    .map(payload -> (Map<String, Object>) objectMapper.readValue(payload, Map.class))
                    .toList();
        }
    }
}

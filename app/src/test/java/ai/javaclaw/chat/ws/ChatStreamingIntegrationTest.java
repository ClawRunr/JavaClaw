package ai.javaclaw.chat.ws;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.chat.ChatChannel;
import ai.javaclaw.chat.InMemoryChatSessions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full WebSocket round trip: a userMessage frame is sent to /ws/chat and the streamed
 * response must arrive as multiple JSON chunk frames followed by a done frame.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ChatStreamingIntegrationTest.TestConfig.class)
class ChatStreamingIntegrationTest {

    @LocalServerPort
    int port;

    final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void userMessageIsAnsweredWithMultipleChunkFramesBeforeDone() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        received.add(message.getPayload());
                    }
                }, "ws://localhost:" + port + "/ws/chat")
                .get(5, TimeUnit.SECONDS);

        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "userMessage",
                    "sessionId", shownSessionId(received),
                    "message", "hi"
            ))));

            List<String> frameTypes = collectFrameTypesUntilDone(received);

            assertThat(frameTypes)
                    .containsSubsequence("chunk", "chunk", "done")
                    .last().isEqualTo("done");
        } finally {
            session.close();
        }
    }

    /** The session the first view shows: the tab sends its messages there. */
    private static String shownSessionId(BlockingQueue<String> received) {
        return await("a view").atMost(Duration.ofSeconds(10))
                .until(() -> sessionShownIn(received.poll()), Objects::nonNull);
    }

    /** The session a view frame shows, or null for any other payload or none. */
    private static String sessionShownIn(String payload) {
        if (payload == null) return null;
        Matcher view = SHOWN_SESSION.matcher(payload);
        return view.find() ? view.group(1) : null;
    }

    private static final Pattern SHOWN_SESSION = Pattern.compile("id=\"chat-messages\" data-session-id=\"([^\"]+)\"");

    /**
     * Collects the {@code type} of every JSON frame until the done frame arrives.
     * Non-JSON payloads (htmx HTML fragments) are ignored.
     */
    private List<String> collectFrameTypesUntilDone(BlockingQueue<String> received) {
        List<String> frameTypes = new ArrayList<>();
        await("the done frame").atMost(Duration.ofSeconds(10)).until(() -> {
            for (String payload = received.poll(); payload != null; payload = received.poll()) {
                if (!payload.startsWith("{")) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> frame = objectMapper.readValue(payload, Map.class);
                frameTypes.add((String) frame.get("type"));
                if ("done".equals(frame.get("type"))) return true;
            }
            return false;
        });
        return frameTypes;
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({WebSocketConfig.class, ChatWebSocketHandler.class, ChatChannel.class, InMemoryChatSessions.class})
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
        Agent agent() {
            return new Agent() {
                @Override
                public String respondTo(AgentRequest request) {
                    return "Hello world";
                }

                @Override
                public void respondTo(AgentRequest request, Consumer<AgentEvent> events) {
                    events.accept(new AgentEvent.Token("Hello "));
                    events.accept(new AgentEvent.Token("world"));
                    events.accept(new AgentEvent.Done());
                }

                @Override
                public <T> T prompt(AgentRequest request, Class<T> result) {
                    return null;
                }
            };
        }
    }
}

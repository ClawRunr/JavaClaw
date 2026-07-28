package ai.javaclaw.chat.ws;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.chat.ChatChannel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
                    "conversationId", "web",
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

    /**
     * Collects the {@code type} of every JSON frame until the done frame arrives.
     * Non-JSON payloads (htmx HTML fragments) are ignored.
     */
    private List<String> collectFrameTypesUntilDone(BlockingQueue<String> received) throws Exception {
        List<String> frameTypes = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String payload = received.poll(500, TimeUnit.MILLISECONDS);
            if (payload == null || !payload.startsWith("{")) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(payload, Map.class);
            frameTypes.add((String) frame.get("type"));
            if ("done".equals(frame.get("type"))) break;
        }
        return frameTypes;
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({WebSocketConfig.class, ChatWebSocketHandler.class, ChatChannel.class})
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
        ChatMemoryRepository chatMemoryRepository() {
            return new InMemoryChatMemoryRepository();
        }

        @Bean
        Agent agent() {
            return new Agent() {
                @Override
                public String respondTo(String conversationId, String question) {
                    return "Hello world";
                }

                @Override
                public String respondTo(String conversationId, String question, ResponseListener listener) {
                    listener.onToken("Hello ");
                    listener.onToken("world");
                    listener.onComplete();
                    return "Hello world";
                }

                @Override
                public <T> T prompt(String conversationId, String input, Class<T> result) {
                    return null;
                }
            };
        }
    }
}

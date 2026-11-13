package ai.javaclaw.chat.ws;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.DefaultAgent;
import ai.javaclaw.approval.ApprovalPolicy;
import ai.javaclaw.approval.ApprovalProperties;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.chat.ChatChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval round trip over a real WebSocket: the model asks for a tool, the turn pauses and
 * the browser gets an {@code approvalRequested} frame, answers it, and the resumed turn streams the
 * tool's result and the answer. A pause outlives the socket: a client that comes back is shown it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ChatApprovalIntegrationTest.TestConfig.class)
class ChatApprovalIntegrationTest {

    static final List<String> toolRuns = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;

    final ObjectMapper objectMapper = new ObjectMapper();
    final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();

    @AfterEach
    void reset() {
        toolRuns.clear();
    }

    @Test
    void approvingOnTheSameSocketRunsTheToolOnceAndTheAnswerArrives() throws Exception {
        WebSocketSession session = connect();
        try {
            send(session, Map.of("type", "userMessage", "conversationId", "web", "message", "note: buy milk"));

            Map<String, Object> request = data(frameOfType("approvalRequested"));
            assertThat(request).containsEntry("toolName", "writeNote")
                    .containsEntry("arguments", "{\"text\":\"buy milk\"}")
                    .containsEntry("decisions", List.of("allow-once", "deny"));
            assertThat(toolRuns).isEmpty();

            send(session, Map.of("type", "approvalDecision", "conversationId", "web",
                    "approvalId", request.get("approvalId"),
                    "argumentsHash", request.get("argumentsHash"),
                    "decision", "allow-once"));

            assertThat(data(frameOfType("approvalResolved"))).containsEntry("outcome", "allowed");
            assertThat(data(frameOfType("toolResult"))).containsEntry("output", "saved");
            assertThat(frameOfType("chunk").get("data")).isEqualTo("Done: saved");
            frameOfType("done");
            assertThat(toolRuns).containsExactly("{\"text\":\"buy milk\"}");
        } finally {
            session.close();
        }
    }

    @Test
    void aTamperedDecisionIsIgnoredAndTheToolDoesNotRun() throws Exception {
        WebSocketSession session = connect();
        try {
            send(session, Map.of("type", "userMessage", "conversationId", "web", "message", "note: buy milk"));
            Map<String, Object> request = data(frameOfType("approvalRequested"));

            send(session, Map.of("type", "approvalDecision", "conversationId", "web",
                    "approvalId", request.get("approvalId"),
                    "argumentsHash", "0000",
                    "decision", "allow-once"));
            send(session, Map.of("type", "approvalDecision", "conversationId", "web",
                    "approvalId", request.get("approvalId"),
                    "argumentsHash", request.get("argumentsHash"),
                    "decision", "deny"));

            assertThat(data(frameOfType("approvalResolved")))
                    .containsEntry("outcome", "denied").containsEntry("detail", "The user did not approve this action.");
            frameOfType("done");
            assertThat(toolRuns).isEmpty();
        } finally {
            session.close();
        }
    }

    @Test
    void aPauseOutlivesTheSocketAndCanBeAnsweredAfterReconnecting() throws Exception {
        WebSocketSession first = connect();
        send(first, Map.of("type", "userMessage", "conversationId", "web", "message", "note: buy milk"));
        String approvalId = (String) data(frameOfType("approvalRequested")).get("approvalId");
        first.close();

        WebSocketSession second = connect();
        try {
            Map<String, Object> request = data(frameOfType("approvalRequested"));
            assertThat(request).containsEntry("approvalId", approvalId);
            assertThat(toolRuns).isEmpty();

            send(second, Map.of("type", "approvalDecision", "conversationId", "web",
                    "approvalId", approvalId,
                    "argumentsHash", request.get("argumentsHash"),
                    "decision", "allow-once"));

            assertThat(frameOfType("chunk").get("data")).isEqualTo("Done: saved");
            assertThat(toolRuns).containsExactly("{\"text\":\"buy milk\"}");
        } finally {
            second.close();
        }
    }

    private WebSocketSession connect() throws Exception {
        return connect(port, "http://localhost:" + port, frames);
    }

    /**
     * Opens the chat socket the way a browser on {@code origin} would, collecting its JSON frames.
     *
     * @param origin the {@code Origin} header to send, or {@code null} to send none
     */
    static WebSocketSession connect(int port, String origin, BlockingQueue<Map<String, Object>> frames)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        if (message.getPayload().startsWith("{")) {
                            frames.add(json.readValue(message.getPayload(), Map.class));
                        }
                    }
                }, headers, URI.create("ws://localhost:" + port + "/ws/chat"))
                .get(5, TimeUnit.SECONDS);
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws Exception {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }

    private Map<String, Object> frameOfType(String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> frame = frames.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null && type.equals(frame.get("type"))) {
                return frame;
            }
        }
        throw new AssertionError("No " + type + " frame arrived");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("data");
    }

    /** Asks for {@code writeNote} once, then reports the tool's result. */
    static class NoteModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                    .findFirst()
                    .map(r -> new ChatResponse(List.of(new Generation(new AssistantMessage(
                            "Done: " + r.responseData())))))
                    .orElseGet(() -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "writeNote",
                                    "{\"text\":\"buy milk\"}")))
                            .build()))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    static class WriteNoteTool implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("writeNote").description("Writes a note")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            toolRuns.add(toolInput);
            return "saved";
        }
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
            ApprovalPolicy policy = new ApprovalPolicy(ApprovalProperties.defaults());
            return new DefaultAgent(ChatClient.builder(new NoteModel())
                    .defaultTools(new WriteNoteTool())
                    .defaultAdvisors(ToolCallingAdvisor.builder()
                            .toolCallingManager(policy.toolCallingManager())
                            .build())
                    .build(), policy);
        }
    }
}

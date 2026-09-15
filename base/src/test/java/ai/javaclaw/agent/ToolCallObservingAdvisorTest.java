package ai.javaclaw.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolCallObservingAdvisorTest {

    @Mock StreamAdvisorChain chain;

    ToolCallObservingAdvisor advisor;
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        advisor = new ToolCallObservingAdvisor("web", event -> {});
        logs = captureLogsOf(ToolCallObservingAdvisor.class);
    }

    @Test
    void runsInsideTheToolLoopWhereToolCallsAreStillVisible() {
        assertThat(advisor.getOrder()).isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    @Test
    void logsEachToolTheModelCalls() {
        when(chain.nextStream(any())).thenReturn(Flux.just(responseWith(toolCall("call-1", "readFile"))));

        advisor.adviseStream(request(new UserMessage("hi")), chain).blockLast();

        assertThat(infoMessages()).containsExactly("Conversation web calls tool readFile");
    }

    @Test
    void logsAToolCallOnceEvenThoughArgumentsArriveAcrossChunks() {
        when(chain.nextStream(any())).thenReturn(Flux.just(
                responseWith(toolCall("call-1", "readFile")),
                responseWith(toolCall("call-1", "readFile")),
                responseWith(toolCall("call-1", "readFile"))));

        advisor.adviseStream(request(new UserMessage("hi")), chain).blockLast();

        assertThat(infoMessages()).containsExactly("Conversation web calls tool readFile");
    }

    @Test
    void logsCompletionWhenTheNextRoundCarriesTheToolResponse() {
        when(chain.nextStream(any())).thenReturn(Flux.just(responseWith(toolCall("call-1", "readFile"))));
        advisor.adviseStream(request(new UserMessage("hi")), chain).blockLast();

        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "readFile", "file contents")))
                .build();
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        advisor.adviseStream(request(new UserMessage("hi"), toolResponse), chain).blockLast();

        assertThat(infoMessages()).containsExactly(
                "Conversation web calls tool readFile",
                "Conversation web finished tool readFile");
    }

    @Test
    void reportsToolNameInputAndOutputToTheListener() {
        List<AgentEvent> seen = new ArrayList<>();
        ToolCallObservingAdvisor observed = new ToolCallObservingAdvisor("web", seen::add);

        when(chain.nextStream(any())).thenReturn(Flux.just(responseWith(toolCall("call-1", "readFile"))));
        observed.adviseStream(request(new UserMessage("hi")), chain).blockLast();

        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "readFile", "file contents")))
                .build();
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        observed.adviseStream(request(new UserMessage("hi"), toolResponse), chain).blockLast();

        assertThat(seen).containsExactly(
                new AgentEvent.ToolCall("call-1", "readFile", "{\"path\":\"pom.xml\"}"),
                new AgentEvent.ToolResult("call-1", "readFile", "file contents"));
    }

    @Test
    void ignoresOrdinaryTextResponses() {
        when(chain.nextStream(any())).thenReturn(Flux.just(
                ChatClientResponse.builder()
                        .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("hello")))))
                        .build()));

        advisor.adviseStream(request(new UserMessage("hi")), chain).blockLast();

        assertThat(infoMessages()).isEmpty();
    }

    private List<String> infoMessages() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ChatClientRequest request(org.springframework.ai.chat.messages.Message... messages) {
        return ChatClientRequest.builder().prompt(new Prompt(List.of(messages))).build();
    }

    private static AssistantMessage.ToolCall toolCall(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{\"path\":\"pom.xml\"}");
    }

    private static ChatClientResponse responseWith(AssistantMessage.ToolCall call) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(message))))
                .build();
    }

    private static ListAppender<ILoggingEvent> captureLogsOf(Class<?> type) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}

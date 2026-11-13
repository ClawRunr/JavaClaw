package ai.javaclaw.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentTest {

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec streamSpec;
    @Mock ChatClient.CallResponseSpec callSpec;

    /** Events are a stream, so record and assert the sequence rather than mocking callbacks. */
    final List<AgentEvent> events = new ArrayList<>();

    DefaultAgent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agent = new DefaultAgent(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theQuestionIsSentAsTheUserMessage() {
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(text("ok")));

        agent.respondTo("web", "hello", events::add);

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(messages.capture());
        assertThat(messages.getValue()).containsExactly(new UserMessage("hello"));
    }

    @Test
    void reportsEachTokenAndCompletionToListener() {
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(text("Hello "), text("world")));

        agent.respondTo("web", "hello", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Hello "),
                new AgentEvent.Token("world"),
                new AgentEvent.Done());
    }

    @Test
    void reportsErrorAndReturnsPartialResponseWhenStreamFails() {
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(
                Flux.just(text("Hello ")),
                Flux.error(new RuntimeException("boom"))));

        agent.respondTo("web", "hello", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Hello "),
                new AgentEvent.Failed("boom"));
    }

    @Test
    void fallsBackToBlockingCallWhenModelDoesNotSupportStreaming() {
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.error(new UnsupportedOperationException("streaming is not supported")));
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(text("full response"));

        agent.respondTo("web", "hello", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("full response"),
                new AgentEvent.Done());
    }

    @Test
    void aBlockingAnswerReturnsTheText() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(text("full response"));

        assertThat(agent.respondTo("web", "hello")).isEqualTo("full response");
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}

package ai.javaclaw.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(chatClient.prompt("hello")).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
    }

    @Test
    void reportsEachTokenAndCompletionToListener() {
        when(streamSpec.content()).thenReturn(Flux.just("Hello ", "world"));

        agent.respondTo("web", "hello", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Hello "),
                new AgentEvent.Token("world"),
                new AgentEvent.Done());
    }

    @Test
    void reportsErrorAndReturnsPartialResponseWhenStreamFails() {
        when(streamSpec.content()).thenReturn(Flux.concat(
                Flux.just("Hello "),
                Flux.error(new RuntimeException("boom"))));

        agent.respondTo("web", "hello", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Hello "),
                new AgentEvent.Failed("boom"));
    }

    @Test
    void fallsBackToBlockingCallWhenModelDoesNotSupportStreaming() {
        when(streamSpec.content()).thenReturn(Flux.error(new UnsupportedOperationException("streaming is not supported")));
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("full response");

        agent.respondTo("web", "hello", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("full response"),
                new AgentEvent.Done());
    }
}

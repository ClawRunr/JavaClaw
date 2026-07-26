package ai.javaclaw.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentTest {

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec streamSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock ResponseListener listener;

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

        String response = agent.respondTo("web", "hello", listener);

        assertThat(response).isEqualTo("Hello world");
        var inOrder = inOrder(listener);
        inOrder.verify(listener).onToken("Hello ");
        inOrder.verify(listener).onToken("world");
        inOrder.verify(listener).onComplete();
        verify(listener, never()).onError(any());
    }

    @Test
    void reportsErrorAndReturnsPartialResponseWhenStreamFails() {
        when(streamSpec.content()).thenReturn(Flux.concat(
                Flux.just("Hello "),
                Flux.error(new RuntimeException("boom"))));

        String response = agent.respondTo("web", "hello", listener);

        assertThat(response).isEqualTo("Hello ");
        var inOrder = inOrder(listener);
        inOrder.verify(listener).onToken("Hello ");
        inOrder.verify(listener).onError("boom");
        verify(listener, never()).onComplete();
    }

    @Test
    void fallsBackToBlockingCallWhenModelDoesNotSupportStreaming() {
        when(streamSpec.content()).thenReturn(Flux.error(new UnsupportedOperationException("streaming is not supported")));
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("full response");

        String response = agent.respondTo("web", "hello", listener);

        assertThat(response).isEqualTo("full response");
        var inOrder = inOrder(listener);
        inOrder.verify(listener).onToken("full response");
        inOrder.verify(listener).onComplete();
        verify(listener, never()).onError(any());
    }
}

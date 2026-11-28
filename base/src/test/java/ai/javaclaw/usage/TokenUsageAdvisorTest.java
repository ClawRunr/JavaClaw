package ai.javaclaw.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenUsageAdvisorTest {

    @Mock UsageRecorder recorder;
    @Mock CallAdvisorChain callChain;
    @Mock StreamAdvisorChain streamChain;

    TokenUsageAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new TokenUsageAdvisor(recorder);
    }

    @Test
    void callRecordsTheResponseUnderItsSession() {
        ChatClientRequest request = request("20261124_153012_a1b2c3d4");
        ChatClientResponse response = response(100, 40);
        when(callChain.nextCall(request)).thenReturn(response);

        assertThat(advisor.adviseCall(request, callChain)).isSameAs(response);

        verify(recorder).record("20261124_153012_a1b2c3d4", response.chatResponse());
    }

    @Test
    void callWithoutSessionRecordsWithoutOne() {
        ChatClientRequest request = request(null);
        ChatClientResponse response = response(10, 5);
        when(callChain.nextCall(request)).thenReturn(response);

        advisor.adviseCall(request, callChain);

        verify(recorder).record(null, response.chatResponse());
    }

    @Test
    void callWithoutUsageRecordsNothing() {
        ChatClientRequest request = request("s");
        when(callChain.nextCall(request)).thenReturn(response(0, 0));

        advisor.adviseCall(request, callChain);

        verifyNoInteractions(recorder);
    }

    @Test
    void streamRecordsOnlyTheLastUsageOnceItCompletes() {
        ChatClientRequest request = request("s");
        ChatClientResponse inputOnly = response(120, 0);
        ChatClientResponse text = response(0, 0);
        ChatClientResponse last = response(120, 80);
        when(streamChain.nextStream(request)).thenReturn(Flux.just(inputOnly, text, last));

        List<ChatClientResponse> emitted = advisor.adviseStream(request, streamChain).collectList().block();

        assertThat(emitted).containsExactly(inputOnly, text, last);
        verify(recorder).record("s", last.chatResponse());
        verify(recorder, never()).record(any(), eq(inputOnly.chatResponse()));
    }

    @Test
    void streamWithoutUsageRecordsNothing() {
        ChatClientRequest request = request("s");
        when(streamChain.nextStream(request)).thenReturn(Flux.just(response(0, 0), response(0, 0)));

        advisor.adviseStream(request, streamChain).blockLast();

        verifyNoInteractions(recorder);
    }

    @Test
    void failedStreamRecordsNothing() {
        ChatClientRequest request = request("s");
        when(streamChain.nextStream(request)).thenReturn(Flux.concat(
                Flux.just(response(120, 0)),
                Flux.error(new RuntimeException("boom"))));

        assertThatThrownBy(() -> advisor.adviseStream(request, streamChain).blockLast()).hasMessage("boom");

        verifyNoInteractions(recorder);
    }

    @Test
    void runsInsideTheToolLoop() {
        assertThat(advisor.getOrder()).isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    private static ChatClientRequest request(String sessionId) {
        ChatClientRequest.Builder builder = ChatClientRequest.builder().prompt(new Prompt("hello"));
        if (sessionId != null) {
            builder.context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId);
        }
        return builder.build();
    }

    private static ChatClientResponse response(int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("claude-sonnet-4-6")
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))), metadata);
        return ChatClientResponse.builder().chatResponse(chatResponse).build();
    }
}

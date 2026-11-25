package ai.javaclaw.usage;

import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenUsageAdvisorTest {

    @Mock UsageRepository usageRepository;
    @Mock CallAdvisorChain callChain;
    @Mock StreamAdvisorChain streamChain;

    TokenUsageAdvisor advisor;
    LlmProviderProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LlmProviderProperties();
        properties.setProviders(Map.of(
                LlmProviderProperties.DEFAULT_PROVIDER_NAME,
                new ProviderConfig("anthropic", "key", null, "claude-sonnet-4-6")
        ));
        advisor = new TokenUsageAdvisor(usageRepository, properties);
    }

    @Test
    void adviseCallRecordsUsageFromResponse() {
        ChatClientRequest request = requestWithConversation("telegram-42");
        ChatClientResponse response = responseWithUsage(100, 40, "claude-sonnet-4-6");
        when(callChain.nextCall(request)).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, callChain);

        assertThat(result).isSameAs(response);
        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRepository).save(captor.capture());
        UsageRecord record = captor.getValue();
        assertThat(record.promptTokens()).isEqualTo(100);
        assertThat(record.completionTokens()).isEqualTo(40);
        assertThat(record.totalTokens()).isEqualTo(140);
        assertThat(record.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(record.provider()).isEqualTo("anthropic");
        assertThat(record.source()).isEqualTo(UsageSource.TELEGRAM);
        assertThat(record.conversationId()).isEqualTo("telegram-42");
        assertThat(record.taskId()).isNull();
    }

    @Test
    void adviseCallSetsTaskIdForScheduledTasks() {
        ChatClientRequest request = requestWithConversation("task-2026-11-25-100000-buy_milk");
        when(callChain.nextCall(request)).thenReturn(responseWithUsage(10, 5, "m"));

        advisor.adviseCall(request, callChain);

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRepository).save(captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(UsageSource.SCHEDULED_TASK);
        assertThat(captor.getValue().taskId()).isEqualTo("task-2026-11-25-100000-buy_milk");
    }

    @Test
    void adviseCallSkipsEmptyUsage() {
        ChatClientRequest request = requestWithConversation("web");
        when(callChain.nextCall(request)).thenReturn(responseWithUsage(0, 0, "m"));

        advisor.adviseCall(request, callChain);

        verify(usageRepository, never()).save(any());
    }

    @Test
    void adviseCallDoesNotThrowWhenRepositoryFails() {
        ChatClientRequest request = requestWithConversation("web");
        when(callChain.nextCall(request)).thenReturn(responseWithUsage(1, 1, "m"));
        doThrow(new RuntimeException("disk full")).when(usageRepository).save(any());

        assertThatCode(() -> advisor.adviseCall(request, callChain)).doesNotThrowAnyException();
    }

    @Test
    void adviseStreamRecordsOnceWhenUsageAppears() {
        ChatClientRequest request = requestWithConversation("web");
        ChatClientResponse empty = responseWithUsage(0, 0, "m");
        ChatClientResponse withUsage = responseWithUsage(50, 10, "claude-sonnet-4-6");
        when(streamChain.nextStream(request)).thenReturn(Flux.just(empty, withUsage, withUsage));

        List<ChatClientResponse> emitted = advisor.adviseStream(request, streamChain).collectList().block();

        assertThat(emitted).hasSize(3);
        verify(usageRepository, times(1)).save(any());
    }

    @Test
    void orderIsInsideToolCallingLoop() {
        assertThat(advisor.getOrder()).isGreaterThan(
                org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER);
    }

    private static ChatClientRequest requestWithConversation(String conversationId) {
        return ChatClientRequest.builder()
                .prompt(new Prompt("hello"))
                .context(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, conversationId)
                .build();
    }

    private static ChatClientResponse responseWithUsage(int promptTokens, int completionTokens, String model) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("hi"))),
                metadata
        );
        return ChatClientResponse.builder().chatResponse(chatResponse).build();
    }
}

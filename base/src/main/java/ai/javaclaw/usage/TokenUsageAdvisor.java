package ai.javaclaw.usage;

import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records token usage for every model call, including each round of the tool-calling loop.
 */
@Component
public class TokenUsageAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageAdvisor.class);
    public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 1;

    private final UsageRepository usageRepository;
    private final LlmProviderProperties llmProviderProperties;

    public TokenUsageAdvisor(UsageRepository usageRepository, LlmProviderProperties llmProviderProperties) {
        this.usageRepository = usageRepository;
        this.llmProviderProperties = llmProviderProperties;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        recordFromResponse(request, response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        AtomicBoolean recorded = new AtomicBoolean(false);
        return chain.nextStream(request)
                .doOnNext(response -> {
                    if (recorded.get()) {
                        return;
                    }
                    if (hasMeaningfulUsage(response != null ? response.chatResponse() : null)
                            && recorded.compareAndSet(false, true)) {
                        recordFromResponse(request, response);
                    }
                });
    }

    private void recordFromResponse(ChatClientRequest request, ChatClientResponse response) {
        try {
            ChatResponse chatResponse = response != null ? response.chatResponse() : null;
            if (!hasMeaningfulUsage(chatResponse)) {
                return;
            }
            Usage usage = chatResponse.getMetadata().getUsage();
            String conversationId = conversationId(request);
            UsageSource source = UsageSource.fromConversationId(conversationId);
            ProviderConfig defaults = defaultProviderConfig();

            int promptTokens = nullToZero(usage.getPromptTokens());
            int completionTokens = nullToZero(usage.getCompletionTokens());
            int totalTokens = usage.getTotalTokens() != null
                    ? usage.getTotalTokens()
                    : promptTokens + completionTokens;

            UsageRecord record = new UsageRecord(
                    Instant.now(),
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    resolveModel(chatResponse, defaults),
                    resolveProvider(defaults),
                    source,
                    conversationId != null ? conversationId : "",
                    UsageSource.taskIdFor(conversationId)
            );
            usageRepository.save(record);
        } catch (RuntimeException e) {
            log.warn("Failed to record token usage: {}", e.getMessage());
        }
    }

    static boolean hasMeaningfulUsage(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return false;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return false;
        }
        return nullToZero(usage.getPromptTokens()) > 0 || nullToZero(usage.getCompletionTokens()) > 0;
    }

    private static String conversationId(ChatClientRequest request) {
        if (request == null || request.context() == null) {
            return null;
        }
        Object value = request.context().get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
        return value != null ? value.toString() : null;
    }

    private String resolveModel(ChatResponse chatResponse, ProviderConfig defaults) {
        String model = chatResponse.getMetadata().getModel();
        if (model != null && !model.isBlank()) {
            return model;
        }
        return defaults != null && defaults.getModel() != null ? defaults.getModel() : "";
    }

    private String resolveProvider(ProviderConfig defaults) {
        return defaults != null && defaults.getProvider() != null ? defaults.getProvider() : "";
    }

    private ProviderConfig defaultProviderConfig() {
        if (llmProviderProperties == null || llmProviderProperties.getProviders() == null) {
            return null;
        }
        return llmProviderProperties.getProviders().get(LlmProviderProperties.DEFAULT_PROVIDER_NAME);
    }

    private static int nullToZero(Integer value) {
        return value != null ? value : 0;
    }
}

package ai.javaclaw.providers.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key", havingValue = AnthropicClaudeCodeConfiguration.CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER)
public class AnthropicClaudeCodeConfiguration {

    public static final String CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER = "<claude-code-bearer-token>";

    @Bean
    public AnthropicChatModel anthropicChatModel(AnthropicConnectionProperties connectionProperties,
                                                 AnthropicChatProperties chatProperties, ToolCallingManager toolCallingManager,
                                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                                 ObjectProvider<ChatModelObservationConvention> observationConvention,
                                                 ObjectProvider<ToolExecutionEligibilityPredicate> anthropicToolExecutionEligibilityPredicate) {

        AnthropicChatOptions options = getAnthropicChatOptions(chatProperties);

        var backend = new AnthropicClaudeCodeBackend();
        var chatModel = AnthropicChatModel.builder()
                .anthropicClient(anthropicClient(connectionProperties, backend))
                .anthropicClientAsync(anthropicClientAsync(connectionProperties, backend))
                .options(options)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .toolExecutionEligibilityPredicate(anthropicToolExecutionEligibilityPredicate
                        .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
                .build();

        observationConvention.ifAvailable(chatModel::setObservationConvention);

        return chatModel;
    }

    private static AnthropicChatOptions getAnthropicChatOptions(AnthropicChatProperties chatProperties) {
        var props = chatProperties.getOptions();
        return AnthropicChatOptions.builder()
                .model(props.getModel())
                .temperature(props.getTemperature())
                .topP(props.getTopP())
                .topK(props.getTopK())
                .maxTokens(props.getMaxTokens())
                .stopSequences(props.getStopSequences())
                .build();
    }

    private static AnthropicClient anthropicClient(AnthropicConnectionProperties connectionProperties, AnthropicClaudeCodeBackend backend) {
        var clientBuilder = AnthropicOkHttpClient.builder().backend(backend);
        if (connectionProperties.getTimeout() != null) clientBuilder.timeout(connectionProperties.getTimeout());
        if (connectionProperties.getMaxRetries() != null) clientBuilder.maxRetries(connectionProperties.getMaxRetries());
        if (connectionProperties.getProxy() != null) clientBuilder.proxy(connectionProperties.getProxy());
        return clientBuilder.build();
    }

    private static AnthropicClientAsync anthropicClientAsync(AnthropicConnectionProperties connectionProperties, AnthropicClaudeCodeBackend backend) {
        var asyncClientBuilder = AnthropicOkHttpClientAsync.builder().backend(backend);
        if (connectionProperties.getTimeout() != null) asyncClientBuilder.timeout(connectionProperties.getTimeout());
        if (connectionProperties.getMaxRetries() != null) asyncClientBuilder.maxRetries(connectionProperties.getMaxRetries());
        if (connectionProperties.getProxy() != null) asyncClientBuilder.proxy(connectionProperties.getProxy());
        return asyncClientBuilder.build();
    }

}
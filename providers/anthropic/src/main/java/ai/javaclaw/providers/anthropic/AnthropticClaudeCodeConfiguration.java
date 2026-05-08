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
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key", havingValue = AnthropticClaudeCodeConfiguration.CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER)
public class AnthropticClaudeCodeConfiguration {

    public static final String CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER = "<claude-code-bearer-token>";

    @Bean
    public AnthropicChatModel anthropicChatModel(AnthropicConnectionProperties connectionProperties,
                                                 AnthropicChatProperties chatProperties, ToolCallingManager toolCallingManager,
                                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                                 ObjectProvider<ChatModelObservationConvention> observationConvention,
                                                 ObjectProvider<ToolExecutionEligibilityPredicate> anthropicToolExecutionEligibilityPredicate) {

        AnthropicChatOptions options = getAnthropicChatOptions(connectionProperties, chatProperties);

        var backend = new AnthropicClaudeCodeBackend();
        var chatModel = AnthropicChatModel.builder()
                .anthropicClient(anthropicClient(options, backend))
                .anthropicClientAsync(anthropicClientAsync(options, backend))
                .options(options)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .toolExecutionEligibilityPredicate(anthropicToolExecutionEligibilityPredicate
                        .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
                .build();

        observationConvention.ifAvailable(chatModel::setObservationConvention);

        return chatModel;
    }

    private static AnthropicChatOptions getAnthropicChatOptions(AnthropicConnectionProperties connectionProperties, AnthropicChatProperties chatProperties) {
        AnthropicChatOptions options = chatProperties.getOptions();
        if (connectionProperties.getApiKey() != null) options.setApiKey(connectionProperties.getApiKey());
        if (connectionProperties.getBaseUrl() != null) options.setBaseUrl(connectionProperties.getBaseUrl());
        if (connectionProperties.getTimeout() != null) options.setTimeout(connectionProperties.getTimeout());
        if (connectionProperties.getMaxRetries() != null) options.setMaxRetries(connectionProperties.getMaxRetries());
        if (connectionProperties.getProxy() != null) options.setProxy(connectionProperties.getProxy());
        if (!connectionProperties.getCustomHeaders().isEmpty()) options.setCustomHeaders(connectionProperties.getCustomHeaders());
        return options;
    }

    private static AnthropicClient anthropicClient(AnthropicChatOptions options, AnthropicClaudeCodeBackend backend) {
        var clientBuilder = AnthropicOkHttpClient.builder().backend(backend);
        if (options.getTimeout() != null) clientBuilder.timeout(options.getTimeout());
        if (options.getMaxRetries() != null) clientBuilder.maxRetries(options.getMaxRetries());
        if (options.getProxy() != null) clientBuilder.proxy(options.getProxy());
        return clientBuilder.build();
    }

    private static AnthropicClientAsync anthropicClientAsync(AnthropicChatOptions options, AnthropicClaudeCodeBackend backend) {
        var asyncClientBuilder = AnthropicOkHttpClientAsync.builder().backend(backend);
        if (options.getTimeout() != null) asyncClientBuilder.timeout(options.getTimeout());
        if (options.getMaxRetries() != null) asyncClientBuilder.maxRetries(options.getMaxRetries());
        if (options.getProxy() != null) asyncClientBuilder.proxy(options.getProxy());
        return asyncClientBuilder.build();
    }

}
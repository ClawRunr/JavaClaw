package ai.javaclaw.providers.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.http.okhttp.SpringAiAnthropicHttpClient;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
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
                                                 AnthropicChatProperties chatProperties,
                                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                                 ObjectProvider<ChatModelObservationConvention> observationConvention) {

        AnthropicChatOptions options = getAnthropicChatOptions(connectionProperties, chatProperties);

        var backend = new AnthropicClaudeCodeBackend();
        var client = anthropicClient(options, backend);
        var chatModel = AnthropicChatModel.builder()
                .anthropicClient(client)
                .anthropicClientAsync(client.async())
                .options(options)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();

        observationConvention.ifAvailable(chatModel::setObservationConvention);

        return chatModel;
    }

    private static AnthropicChatOptions getAnthropicChatOptions(AnthropicConnectionProperties connectionProperties, AnthropicChatProperties chatProperties) {
        AnthropicChatOptions.Builder options = chatProperties.toOptions().mutate();
        if (connectionProperties.getApiKey() != null) options.apiKey(connectionProperties.getApiKey());
        if (connectionProperties.getBaseUrl() != null) options.baseUrl(connectionProperties.getBaseUrl());
        if (connectionProperties.getTimeout() != null) options.timeout(connectionProperties.getTimeout());
        if (connectionProperties.getMaxRetries() != null) options.maxRetries(connectionProperties.getMaxRetries());
        if (connectionProperties.getProxy() != null) options.proxy(connectionProperties.getProxy());
        if (!connectionProperties.getCustomHeaders().isEmpty()) options.customHeaders(connectionProperties.getCustomHeaders());
        return options.build();
    }

    private static AnthropicClient anthropicClient(AnthropicChatOptions options, AnthropicClaudeCodeBackend backend) {
        var httpClientBuilder = SpringAiAnthropicHttpClient.builder().backend(backend);
        if (options.getTimeout() != null) httpClientBuilder.timeout(options.getTimeout());
        if (options.getProxy() != null) httpClientBuilder.proxy(options.getProxy());

        var clientOptionsBuilder = ClientOptions.builder().httpClient(httpClientBuilder.build());
        if (options.getMaxRetries() != null) clientOptionsBuilder.maxRetries(options.getMaxRetries());
        return new AnthropicClientImpl(clientOptionsBuilder.build());
    }

}
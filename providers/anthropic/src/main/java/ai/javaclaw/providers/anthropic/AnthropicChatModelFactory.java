package ai.javaclaw.providers.anthropic;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.http.okhttp.SpringAiAnthropicHttpClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import static ai.javaclaw.providers.anthropic.AnthropticClaudeCodeConfiguration.CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER;

/**
 * Builds {@link AnthropicChatModel}. When the API key is the Claude Code OAuth placeholder, the
 * model is wired to the {@link AnthropicClaudeCodeBackend} (system-wide Claude Code token) instead of
 * a plain API key; otherwise the Anthropic client is constructed lazily from the chat options.
 */
@Component
public class AnthropicChatModelFactory implements ChatModelFactory {

    @Override
    public boolean supports(String providerType) {
        return "anthropic".equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder();
        if (config.getModel() != null && !config.getModel().isBlank()) {
            options.model(config.getModel());
        }
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            options.apiKey(config.getApiKey());
        }
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            options.baseUrl(config.getBaseUrl().trim());
        }
        AnthropicChatOptions built = options.build();

        if (CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER.equals(config.getApiKey())) {
            AnthropicClient client = claudeCodeClient();
            return AnthropicChatModel.builder()
                    .anthropicClient(client)
                    .anthropicClientAsync(client.async())
                    .options(built)
                    .build();
        }

        return AnthropicChatModel.builder().options(built).build();
    }

    private static AnthropicClient claudeCodeClient() {
        var httpClient = SpringAiAnthropicHttpClient.builder()
                .backend(new AnthropicClaudeCodeBackend())
                .build();
        ClientOptions clientOptions = ClientOptions.builder().httpClient(httpClient).build();
        return new AnthropicClientImpl(clientOptions);
    }
}

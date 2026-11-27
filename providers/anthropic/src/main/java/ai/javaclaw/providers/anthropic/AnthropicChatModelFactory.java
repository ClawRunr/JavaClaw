package ai.javaclaw.providers.anthropic;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Builds {@link AnthropicChatModel} for an Anthropic API key. The Anthropic client is constructed
 * lazily from the chat options.
 */
@Component
public class AnthropicChatModelFactory implements ChatModelFactory {

    /** The api-key value earlier versions stored to reuse the Claude Code login directly. */
    static final String LEGACY_CLAUDE_CODE_TOKEN = "<claude-code-bearer-token>";

    @Override
    public boolean supports(String providerType) {
        return "anthropic".equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        if (LEGACY_CLAUDE_CODE_TOKEN.equals(config.getApiKey())) {
            throw new IllegalArgumentException("Reusing the Claude Code login as an Anthropic API key is no longer supported. "
                    + "Set this provider to 'provider: claude-code' (and remove its api-key) to use your Claude subscription "
                    + "through the official Claude Code CLI");
        }
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder();
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            options.baseUrl(config.getBaseUrl().trim());
        }
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            options.apiKey(config.getApiKey());
        }
        if (config.getModel() != null && !config.getModel().isBlank()) {
            options.model(config.getModel());
        }
        return AnthropicChatModel.builder().options(options.build()).build();
    }
}

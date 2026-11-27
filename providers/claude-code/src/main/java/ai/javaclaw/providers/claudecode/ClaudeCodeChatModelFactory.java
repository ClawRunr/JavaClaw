package ai.javaclaw.providers.claudecode;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Builds {@link ClaudeCodeChatModel} for the {@code claude-code} provider type: the user's Claude
 * subscription through the official Claude Code CLI. No API key is involved, and a custom base URL is
 * refused: the CLI's subscription credential must only ever reach Anthropic.
 */
@Component
public class ClaudeCodeChatModelFactory implements ChatModelFactory {

    static final String PROVIDER_TYPE = "claude-code";

    @Override
    public boolean supports(String providerType) {
        return PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("The Claude subscription provider does not support a custom base URL; "
                    + "remove base-url from this provider");
        }
        return ClaudeCodeChatModel.builder().model(config.getModel()).build();
    }
}

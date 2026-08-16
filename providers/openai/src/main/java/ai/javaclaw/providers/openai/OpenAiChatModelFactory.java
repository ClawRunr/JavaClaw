package ai.javaclaw.providers.openai;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Builds {@link OpenAiChatModel} for the {@code openai} provider type. The OpenAI client is
 * constructed lazily by the model from the API key / base URL carried on {@link OpenAiChatOptions}.
 */
@Component
public class OpenAiChatModelFactory implements ChatModelFactory {

    @Override
    public boolean supports(String providerType) {
        return "openai".equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        if (config.getModel() != null && !config.getModel().isBlank()) {
            options.model(config.getModel());
        }
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            options.apiKey(config.getApiKey());
        }
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            options.baseUrl(config.getBaseUrl().trim());
        }
        return OpenAiChatModel.builder().options(options.build()).build();
    }
}

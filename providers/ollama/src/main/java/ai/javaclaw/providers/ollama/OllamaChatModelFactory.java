package ai.javaclaw.providers.ollama;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Builds {@link OllamaChatModel} for the local, no-API-key {@code ollama} provider type.
 */
@Component
public class OllamaChatModelFactory implements ChatModelFactory {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";

    @Override
    public boolean supports(String providerType) {
        return "ollama".equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        String baseUrl = (config.getBaseUrl() != null && !config.getBaseUrl().isBlank())
                ? config.getBaseUrl().trim()
                : DEFAULT_BASE_URL;
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();

        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        if (config.getModel() != null && !config.getModel().isBlank()) {
            options.model(config.getModel());
        }
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options.build())
                .build();
    }
}

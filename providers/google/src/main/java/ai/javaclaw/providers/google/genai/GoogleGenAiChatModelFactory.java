package ai.javaclaw.providers.google.genai;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import com.google.genai.Client;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Builds {@link GoogleGenAiChatModel} for the {@code google.genai} provider type. Unlike the other
 * providers, the Google client must be constructed explicitly from the API key.
 */
@Component
public class GoogleGenAiChatModelFactory implements ChatModelFactory {

    @Override
    public boolean supports(String providerType) {
        return "google.genai".equals(providerType)
                || "google-genai".equals(providerType)
                || "google".equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        Client.Builder clientBuilder = Client.builder();
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            clientBuilder.apiKey(config.getApiKey());
        }
        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder();
        if (config.getModel() != null && !config.getModel().isBlank()) {
            options.model(config.getModel());
        }
        return GoogleGenAiChatModel.builder()
                .genAiClient(clientBuilder.build())
                .options(options.build())
                .build();
    }
}

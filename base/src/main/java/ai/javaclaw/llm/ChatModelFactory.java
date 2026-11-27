package ai.javaclaw.llm;

import org.springframework.ai.chat.model.ChatModel;

/**
 * SPI implemented by each provider module to build a {@link ChatModel} from a
 * {@link LlmProviderProperties.ProviderConfig} without relying on Spring AI auto-configuration.
 * <p>
 * Keeping model construction in the provider modules (where the corresponding
 * {@code spring-ai-starter-model-*} dependency lives) lets {@link ChatClientRegistry} build any
 * configured provider while {@code base} stays free of provider-specific dependencies.
 * <p>
 * Implementations are auto-discovered Spring beans.
 */
public interface ChatModelFactory {

    /**
     * @return whether this factory can build a model for the given provider type
     *         (e.g. {@code "openai"}, {@code "anthropic"}, {@code "ollama"}, {@code "google.genai"}).
     */
    boolean supports(String providerType);

    /**
     * Builds a (lazily-connecting) {@link ChatModel}. Construction must not perform any network
     * I/O — connectivity problems surface only when the model is actually called.
     */
    ChatModel create(LlmProviderProperties.ProviderConfig config);
}

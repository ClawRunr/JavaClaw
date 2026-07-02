package ai.javaclaw.llm;

import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ChatClientRegistryTest {

    /** Factory that returns a distinct model instance per provider name (keyed by model id). */
    private static ChatModelFactory factoryReturning(Map<String, ChatModel> modelsByModelId) {
        return new ChatModelFactory() {
            @Override
            public boolean supports(String providerType) {
                return "test".equals(providerType);
            }

            @Override
            public ChatModel create(ProviderConfig config) {
                return modelsByModelId.get(config.getModel());
            }
        };
    }

    private static LlmProviderProperties propertiesWith(Map<String, ProviderConfig> providers) {
        LlmProviderProperties properties = new LlmProviderProperties();
        properties.setProviders(providers);
        return properties;
    }

    @Test
    void missingNameFallsBackToDefault() {
        ChatModel defaultModel = mock(ChatModel.class);
        LlmProviderProperties properties = propertiesWith(Map.of(
                "default", new ProviderConfig("test", null, null, "m-default")));
        DefaultChatClientRegistry registry = new DefaultChatClientRegistry(
                List.of(factoryReturning(Map.of("m-default", defaultModel))), properties);

        assertThat(registry.availableNames()).containsExactly("default");
        assertThat(registry.has("default")).isTrue();
        assertThat(registry.has("missing")).isFalse();
        // An unknown name resolves to the default provider's model.
        assertThat(registry.modelFor("missing")).isSameAs(defaultModel);
        assertThat(registry.get("missing")).isNotNull();
    }

    @Test
    void namedProviderResolvesToItsOwnModel() {
        ChatModel defaultModel = mock(ChatModel.class);
        ChatModel localModel = mock(ChatModel.class);
        LlmProviderProperties properties = propertiesWith(Map.of(
                "default", new ProviderConfig("test", null, null, "m-default"),
                "local", new ProviderConfig("test", null, null, "m-local")));
        DefaultChatClientRegistry registry = new DefaultChatClientRegistry(
                List.of(factoryReturning(Map.of("m-default", defaultModel, "m-local", localModel))),
                properties);

        assertThat(registry.availableNames()).containsExactlyInAnyOrder("default", "local");
        assertThat(registry.modelFor("local")).isSameAs(localModel);
        assertThat(registry.modelFor("default")).isSameAs(defaultModel);
    }

    @Test
    void unknownProviderTypeIsSkippedWithoutFailing() {
        LlmProviderProperties properties = propertiesWith(Map.of(
                "default", new ProviderConfig("nonexistent-type", null, null, "m")));
        DefaultChatClientRegistry registry = new DefaultChatClientRegistry(List.of(), properties);

        assertThat(registry.availableNames()).isEmpty();
        // get(...) throws when nothing is configured, getOrDefault never does.
        assertThatThrownBy(() -> registry.get("default")).isInstanceOf(IllegalStateException.class);
        assertThat(registry.getOrDefault("default")).isNotNull();
    }
}

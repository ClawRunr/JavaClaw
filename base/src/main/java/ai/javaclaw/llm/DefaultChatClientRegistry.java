package ai.javaclaw.llm;

import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link ChatClientRegistry}. Builds one {@link ChatModel} per configured provider via the
 * {@link ChatModelFactory} SPI. Built once per context; configuration changes take effect via a
 * full application restart.
 */
@Component
public class DefaultChatClientRegistry implements ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatClientRegistry.class);

    private static final String NO_MODEL_MESSAGE =
            "No AI model has been configured. Configure a provider under Settings → Model Providers "
                    + "(or finish onboarding) to start chatting.";

    private final List<ChatModelFactory> factories;
    private final LlmProviderProperties properties;

    /** name -> built model. Successfully-built providers only. */
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
    /** Preserves configuration order for {@link #availableNames()}. */
    private final List<String> order = new CopyOnWriteArrayList<>();

    public DefaultChatClientRegistry(List<ChatModelFactory> factories,
                                     LlmProviderProperties properties) {
        this.factories = factories;
        this.properties = properties;
        rebuild();
    }

    public final synchronized void rebuild() {
        Map<String, ProviderConfig> configured = properties.getProviders();
        models.clear();
        order.clear();
        configured.forEach((name, config) -> {
            try {
                ChatModel model = build(name, config);
                if (model != null) {
                    models.put(name, model);
                    order.add(name);
                }
            } catch (RuntimeException e) {
                log.warn("Failed to build chat model for provider '{}' (type '{}'): {}",
                        name, config == null ? null : config.getProvider(), e.getMessage());
            }
        });
        log.info("Chat client registry built with providers: {}", order);
    }

    private ChatModel build(String name, ProviderConfig config) {
        if (config == null || config.getProvider() == null || config.getProvider().isBlank()) {
            log.warn("Provider '{}' has no 'provider' type configured; skipping", name);
            return null;
        }
        String type = config.getProvider().trim().toLowerCase();
        ChatModelFactory factory = factories.stream()
                .filter(f -> f.supports(type))
                .findFirst()
                .orElse(null);
        if (factory == null) {
            log.warn("No ChatModelFactory available for provider type '{}' (provider '{}'); skipping",
                    type, name);
            return null;
        }
        return factory.create(config);
    }

    @Override
    public ChatClient get(String name) {
        ChatModel model = resolveModel(name);
        if (model == null) {
            throw new IllegalStateException("No chat model configured for provider '" + name
                    + "' and no default provider is available");
        }
        return ChatClient.builder(model).build();
    }

    @Override
    public ChatClient getOrDefault(String name) {
        ChatModel model = resolveModel(name);
        return ChatClient.builder(model != null ? model : placeholderModel()).build();
    }

    @Override
    public ChatClient.Builder builderFor(String name) {
        ChatModel model = resolveModel(name);
        return ChatClient.builder(model != null ? model : placeholderModel());
    }

    @Override
    public ChatModel modelFor(String name) {
        return resolveModel(name);
    }

    @Override
    public Set<String> availableNames() {
        return Set.copyOf(order);
    }

    @Override
    public boolean has(String name) {
        return models.containsKey(name);
    }

    /** Resolves the model for a name, falling back to the default provider, or {@code null}. */
    private ChatModel resolveModel(String name) {
        ChatModel model = models.get(name);
        if (model != null) {
            return model;
        }
        if (name != null && !LlmProviderProperties.DEFAULT_PROVIDER_NAME.equals(name) && !models.isEmpty()) {
            log.warn("Provider '{}' is not configured; falling back to '{}'",
                    name, LlmProviderProperties.DEFAULT_PROVIDER_NAME);
        }
        return models.get(LlmProviderProperties.DEFAULT_PROVIDER_NAME);
    }

    private ChatModel placeholderModel() {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(NO_MODEL_MESSAGE))));
    }
}

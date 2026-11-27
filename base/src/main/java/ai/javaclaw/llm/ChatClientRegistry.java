package ai.javaclaw.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Set;

/**
 * Builds and caches a named {@link ChatClient} for each configured provider
 * (see {@link LlmProviderProperties}). Rebuilt at runtime when the provider configuration changes,
 * so adding/editing providers takes effect without restarting the application.
 */
public interface ChatClientRegistry {

    /**
     * Returns the client for the given name, falling back to {@value LlmProviderProperties#DEFAULT_PROVIDER_NAME}
     * if the name is not configured.
     *
     * @throws IllegalStateException if neither the requested name nor a default provider is configured
     */
    ChatClient get(String name);

    /**
     * Like {@link #get(String)} but never throws — if nothing is configured, returns a placeholder
     * client that responds with a "no model configured" message.
     */
    ChatClient getOrDefault(String name);

    /**
     * Returns a fresh {@link ChatClient.Builder} for the given name (falling back to the default
     * provider), so callers can layer on their own tools/advisors. Used to wire the main agent
     * client and per-provider subagent routing.
     */
    ChatClient.Builder builderFor(String name);

    /**
     * Returns the underlying {@link ChatModel} for the given name (falling back to the default
     * provider), or {@code null} if nothing is configured. Used to wrap models for fallback routing.
     */
    ChatModel modelFor(String name);

    /** The names of all currently-configured providers. */
    Set<String> availableNames();

    /** Whether a provider with the given name is currently configured (and built successfully). */
    boolean has(String name);
}
